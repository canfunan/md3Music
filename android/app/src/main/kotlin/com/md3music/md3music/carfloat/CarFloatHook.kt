package com.md3music.md3music.carfloat

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout

/**
 * 车机悬浮协议（2026-11-02 全面对齐星环注入器 payload 权威实现：
 * 反编译 assets/payload.dex → cn.kuwo.kwmusiccar.FloatWindow 逐条比对重排）：
 *
 *  · 摘【decor.getChildAt(0)】内容根 View —— decor 留在 Activity 窗口当空壳
 *  · ★顺序对齐 payload（黑屏根因修复）：摘 → 塞容器 → **先挂悬浮窗** → fits/padding
 *    → FlutterView detach+attach（此时 View 已 attach to window，Surface 才建得出来）
 *    → 引擎 lifecycle 强制 resumed（宿主退场后 delegate 会发 paused 停渲染管线，
 *      必须补发回来）→ post 重绘 → **最后 moveTaskToBack**（payload pushHostBack
 *      同款时机）→ ACK。旧版在挂窗前退场 = 引擎 paused + 无 Surface = 永久黑屏。
 *  · closemap 延迟 700ms + showSeq 防竞态（payload Receiver$1 同款）：700ms 内来了
 *    新 showmap 则丢弃这条 close —— 海鸥 resize 走 close→show，立即 close 会把
 *    刚挂的新窗拆掉（拉伸掉挂根因之一）
 *  · 「用户在前台门」（payload doShow 同款）：resumed 的 MainActivity 收到 showmap
 *    → 不摘屏，记 pendingRect 等退出后 catchup 自动挂（用户正用时绝不抢屏）
 *  · pendingShow + catchup 追赶（payload scheduleCatchup 同款）：应用首帧未就绪/
 *    无前台 Activity 时记 pendingRect，倍增间隔重试（1.2s 起，5 次封顶），
 *    onActivityPaused 时立即补试 —— 绑定后不用手动再触发
 *  · 权限双查（payload canOverlay 同款）：Settings.canDrawOverlays + appops
 *    checkOp(24)；无权限跳授权页后 permPoll 每秒轮询，授权回来自动补挂
 *  · 悬浮期间 MainActivity resume → moveTaskToBack 压回保槽位（payload LifecycleBridge
 *    同款，还原风暴的正解是「压回」不是「不还原」）+ 引擎 lifecycle 补发
 *  · flags = 0x120（LAYOUT_IN_SCREEN | NOT_TOUCH_MODAL，可聚焦）；type = 2038/2002
 *  · 还原：内容塞回原父容器 + 引擎 detach+attach；宿主销毁 → 容器摘除 + 全引用重置
 */
object CarFloatHook {

    private const val TAG = "Md3CarFloat"

    const val ACTION_SHOW = "com.autonavi.plus.showmap"
    const val ACTION_CLOSE = "com.autonavi.plus.closemap"
    const val ACTION_FLOATED = "cn.kuwo.plus.floated"
    const val ACTION_NOVIEW = "cn.kuwo.plus.noview"

    private const val FLAG_FLOAT = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL   // 0x120，payload 同款

    private val UI = Handler(Looper.getMainLooper())

    /** 服务直投入口（CarFloatKeepService onStartCommand 调）：binder 解冻 + 主线程挂窗。 */
    fun show(x: Int, y: Int, right: Int, bottom: Int) {
        onShowMap(x, y, right, bottom)
    }

    /** 服务直投关闭入口（extra close=1）。 */
    fun close() {
        closeFloat()
    }

    @Volatile private var app: Application? = null
    @Volatile private var resumedActivity: Activity? = null   // resume 记 / pause 清（payload 同款）
    @Volatile private var topActivity: Activity? = null       // 最后存活的 Activity（findContent 用）
    @Volatile private var container: FrameLayout? = null
    @Volatile private var content: View? = null
    @Volatile private var originalParent: ViewGroup? = null
    @Volatile private var floatingRect: IntArray? = null
    /** pendingShow + catchup：摘不到/前台占用时记矩形，倍增间隔重试（payload 同款）。 */
    @Volatile private var pendingRect: IntArray? = null
    private var catchupTries = 0
    private var catchupRunnable: Runnable? = null
    /** show 计数：close 延迟窗口内来了新 show 则丢弃 close（payload showSeq 同款）。 */
    @Volatile private var showSeq = 0
    private var permPoll: Runnable? = null

    /* ==================== 黑匣子（payload logWriter 同款）：文件日志供真机定位 ==================== */

    private var logWriter: java.io.FileWriter? = null
    private val logLock = Any()

    private fun log(msg: String) {
        Log.i(TAG, msg)
        try {
            val a = app ?: return
            synchronized(logLock) {
                if (logWriter == null) {
                    val f = java.io.File(a.getExternalFilesDir(null), "md3float.log")
                    if (f.exists() && f.length() > 256 * 1024) f.delete()   // 单文件封顶，满了重开
                    logWriter = java.io.FileWriter(f, true)
                }
                logWriter!!.append(java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date()))
                    .append(' ').append(msg).append('\n').flush()
            }
        } catch (_: Throwable) {}
    }

    private fun logW(msg: String) { log("W $msg") }

    @JvmStatic
    fun install(application: Application) {
        app = application
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                resumedActivity = activity
                topActivity = activity
                if (container != null && activity.window != null &&
                        activity.javaClass.simpleName.endsWith("MainActivity")) {
                    // 悬浮期间宿主又被拉到前台 → 压回保槽位（payload 同款），
                    // 引擎 lifecycle 随 onStop 变 paused，稍后强制补发 resumed
                    try {
                        activity.moveTaskToBack(true)
                        log("宿主悬浮期被拉前台 → 压回保槽位")
                    } catch (_: Throwable) {}
                    forceEngineResumed()
                }
            }

            override fun onActivityPaused(activity: Activity) {
                if (resumedActivity === activity) resumedActivity = null
                // 宿主刚退到后台：pendingRect 在手就立刻补试（全自动挂载的衔接点）
                if (pendingRect != null && container == null) scheduleCatchup(0)
            }

            override fun onActivityDestroyed(activity: Activity) {
                if (topActivity === activity) topActivity = null
                // 宿主销毁：内容随 Activity 死亡，容器必须摘除 + 全引用重置（payload 同款）
                if (container != null) {
                    removeContainer()
                }
            }
            override fun onActivityCreated(a: Activity, s: android.os.Bundle?) = Unit
            override fun onActivityStarted(a: Activity) = Unit
            override fun onActivityStopped(a: Activity) = Unit
            override fun onActivitySaveInstanceState(a: Activity, o: android.os.Bundle) = Unit
        })

        val rx = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                try {
                    when (intent.action) {
                        ACTION_SHOW -> onShowMap(
                            intent.getIntExtra("x", 0), intent.getIntExtra("y", 0),
                            intent.getIntExtra("w", 0), intent.getIntExtra("h", 0))
                        ACTION_CLOSE -> onCloseMap()
                    }
                } catch (t: Throwable) {
                    logW("onReceive: ${t.message}")
                    reply(ACTION_NOVIEW)
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(ACTION_SHOW)
            addAction(ACTION_CLOSE)
        }
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                application.registerReceiver(rx, filter, Context.RECEIVER_EXPORTED)
            } else {
                application.registerReceiver(rx, filter)
            }
            log("车机悬浮协议已挂（showmap/floated）")
        } catch (t: Throwable) {
            logW("协议注册失败: ${t.message}")
        }
        // 无 root 保活：FGS 载体进程冻结引擎不碰（greezer 对裸进程秒冻 = showmap 被 SKIPPED）
        try {
            CarFloatKeepService.ensure(application)
        } catch (t: Throwable) {
            logW("保活服务拉起失败: ${t.message}")
        }
    }

    /* ==================== showmap 主链（payload doShow/showFloat 对齐版） ==================== */

    /** showmap。w/h = 右下角坐标语义（海鸥按猴哥协议发），先转宽高。 */
    private fun onShowMap(x: Int, y: Int, right: Int, bottom: Int) {
        val a = app ?: return reply(ACTION_NOVIEW)
        val w = right - x
        val h = bottom - y
        log("showmap 收到 right($x,$y,$right,$bottom) -> size($w,$h)")
        if (w <= 0 || h <= 0) return reply(ACTION_NOVIEW)
        showSeq++
        pendingRect = intArrayOf(x, y, w, h)
        catchupTries = 0
        cancelCatchup()

        // 权限双查（payload canOverlay：canDrawOverlays + appops checkOp 24）
        if (!canOverlay(a)) {
            log("showmap：无悬浮权限，跳授权页 + 轮询补挂")
            reply(ACTION_NOVIEW)
            requestOverlayPermission(a)
            startPermPoll(a)
            return
        }

        // 已浮着：同矩形幂等（海鸥重挂会重发）；矩形变化 → 容器 updateViewLayout
        val cur = content
        if (container != null && cur != null) {
            val r = floatingRect
            if (r != null && r[0] == x && r[1] == y && r[2] == w && r[3] == h) {
                reply(ACTION_FLOATED)
                return
            }
            try {
                appWindowManager().updateViewLayout(container, buildParams(x, y, w, h))
                cur.requestLayout()   // Compose/Flutter 按新尺寸重组
                floatingRect = intArrayOf(x, y, w, h)
                pendingRect = null
                reply(ACTION_FLOATED)
                log("悬浮窗重定位 rect($x,$y,$w,$h)")
            } catch (t: Throwable) {
                logW("重定位失败: ${t.message}")
                reply(ACTION_NOVIEW)
            }
            return
        }

        // ★用户在前台门（payload doShow 同款）：用户正在应用里 → 不摘屏，
        // 记 pendingRect 等他退出，onActivityPaused 时 catchup 自动挂
        val act = resumedActivity ?: topActivity
        if (act != null && act === resumedActivity && !act.isFinishing) {
            log("showmap：用户在应用内，保持全屏（等退出后自动挂）")
            scheduleCatchup(1200)
            return
        }

        showFloat(x, y, w, h)
    }

    private fun showFloat(x: Int, y: Int, w: Int, h: Int) {
        val a = app ?: return
        val act = topActivity
        if (act == null || act.window == null) {
            log("showmap：无前台 Activity，pending 等就绪")
            scheduleCatchup(1200)
            return
        }
        try {
            val c = findContent(act) ?: run {
                log("showFloat: content not ready（首帧未就绪）")
                scheduleCatchup(1200)
                return
            }
            val parent = c.parent as? ViewGroup
            if (parent == null) {
                scheduleCatchup(1200)
                return
            }
            // 1) 内容从原父容器摘出
            parent.removeView(c)
            originalParent = parent
            // 2) 自建深色容器（QQ 车机风格底色），内容【全幅】跟随画框
            val box = FrameLayout(act)
            box.background = ColorDrawable(Color.parseColor("#0E1114"))
            box.addView(c, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            // 3) ★先挂系统悬浮窗（payload 顺序：addView 成功后才做收尾与退场）
            appWindowManager().addView(box, buildParams(x, y, w, h))
            container = box
            content = c
            floatingRect = intArrayOf(x, y, w, h)
            pendingRect = null
            // 4) 挂窗成功后的收尾（payload 同款时机）：去 inset 残留，内容满幅
            c.fitsSystemWindows = false
            c.setPadding(0, 0, 0, 0)
            // 5) ★Flutter 黑屏对抗（View 已 attach to window，Surface 才建得出来）：
            //    a. 官方 API 重挂引擎：detach + attach 强制 FlutterRenderer 拿新窗口的新 Surface
            //    b. 钉回不透明背景（launch 动画设过 TRANSPARENT，表面没画出来时整片黑）
            //    c. 引擎 lifecycle 强制 resumed（宿主退场后 delegate 发 paused 停渲染管线）
            if (c is io.flutter.embedding.android.FlutterView) {
                try {
                    val eng = c.attachedFlutterEngine
                    if (eng != null) {
                        c.detachFromFlutterEngine()
                        c.attachToFlutterEngine(eng)
                        log("FlutterView 引擎已重挂（跨窗口 Surface 重建）")
                    }
                } catch (t: Throwable) { logW("引擎重挂失败: ${t.message}") }
                try { c.setBackgroundColor(Color.parseColor("#0E1114")) } catch (_: Throwable) {}
            }
            c.post {
                c.invalidate()
                c.requestLayout()
                c.postDelayed({ c.invalidate() }, 120)
            }
            forceEngineResumed()
            // 6) ★最后宿主退场（payload pushHostBack 同款时机）：桌面上只剩
            //    海鸥 + 悬浮窗，decor 空壳黑屏不出现
            try {
                act.moveTaskToBack(true)
                log("宿主已退后台（pushHostBack）")
            } catch (_: Throwable) {}
            forceEngineResumed()   // moveTaskToBack 的 onStop 会再发 paused，压回去
            reply(ACTION_FLOATED)
            log("悬浮成功 rect($x,$y,$w,$h)，已回 ACK")
        } catch (t: Throwable) {
            logW("悬浮失败: ${t.message}")
            // 失败把内容还回去，应用别黑掉
            restoreContentToWindow()
            reply(ACTION_NOVIEW)
        }
    }

    /** 引擎 lifecycle 强制 resumed + focused（宿主退场后停渲染管线的唯一解药）。 */
    private fun forceEngineResumed() {
        UI.postDelayed({
            try {
                val c = content ?: return@postDelayed
                if (c !is io.flutter.embedding.android.FlutterView) return@postDelayed
                val eng = c.attachedFlutterEngine ?: return@postDelayed
                val ch = eng.lifecycleChannel
                ch.appIsResumed()
                ch.aWindowIsFocused()
                log("引擎 lifecycle 强制 resumed（保悬浮渲染）")
            } catch (t: Throwable) {
                logW("引擎 lifecycle 补发失败: ${t.message}")
            }
        }, 600)
    }

    /* ==================== pending + catchup（payload scheduleCatchup 同款） ==================== */

    private fun scheduleCatchup(delayMs: Long) {
        if (pendingRect == null) return
        if (catchupTries >= 5) {
            log("catchup 放弃（5 次）")
            return
        }
        cancelCatchup()
        val r = Runnable {
            catchupRunnable = null
            val rect = pendingRect ?: return@Runnable
            if (container != null) return@Runnable
            // 用户还在应用里 → 继续等（onActivityPaused 会补试）
            if (resumedActivity != null) return@Runnable
            catchupTries++
            log("catchup #$catchupTries rect(${rect[0]},${rect[1]},${rect[2]},${rect[3]})")
            onShowMap(rect[0], rect[1], rect[0] + rect[2], rect[1] + rect[3])
        }
        catchupRunnable = r
        UI.postDelayed(r, delayMs)
    }

    private fun cancelCatchup() {
        catchupRunnable?.let { UI.removeCallbacks(it) }
        catchupRunnable = null
    }

    /* ==================== closemap（payload 700ms defer + showSeq 防竞态） ==================== */

    private fun onCloseMap() {
        if (container == null) {
            log("closemap: not floating")
            return
        }
        val seqAtClose = showSeq
        log("closemap -> defer 700ms (seq=$seqAtClose)")
        UI.postDelayed({
            if (showSeq != seqAtClose) {
                log("closemap dropped：延迟期内来了新 showmap（resize 防竞态）")
                return@postDelayed
            }
            closeFloat()
        }, 700)
    }

    /* ==================== 权限（payload canOverlay/checkOp + permPoll 同款） ==================== */

    private fun canOverlay(ctx: Context): Boolean {
        if (!try { android.provider.Settings.canDrawOverlays(ctx) } catch (_: Throwable) { false }) {
            return false
        }
        // appops 双查：个别 ROM canDrawOverlays=true 但 appops 拒绝 → addView 直接炸
        // OP_SYSTEM_ALERT_WINDOW(int) 是 @hide，用公开字符串 OPSTR（API 29+）
        if (Build.VERSION.SDK_INT < 29) return true   // 老系统 canDrawOverlays 即权威
        return try {
            val am = ctx.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
            val mode = am.checkOpNoThrow(
                android.app.AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW,
                android.os.Process.myUid(), ctx.packageName)
            mode == android.app.AppOpsManager.MODE_ALLOWED ||
                    mode == android.app.AppOpsManager.MODE_DEFAULT
        } catch (_: Throwable) { true }
    }

    private fun requestOverlayPermission(ctx: Context) {
        try {
            ctx.startActivity(Intent(
                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:${ctx.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (t: Throwable) {
            logW("跳授权页失败: ${t.message}")
        }
    }

    /** 授权页轮询：授权回来自动补挂 pendingRect（payload schedulePermPoll 同款）。 */
    private fun startPermPoll(ctx: Context) {
        permPoll?.let { UI.removeCallbacks(it) }
        var tries = 0
        val r = object : Runnable {
            override fun run() {
                val rect = pendingRect
                if (rect == null || container != null) { permPoll = null; return }
                if (tries++ > 30) { permPoll = null; return }   // 30 秒封顶
                if (canOverlay(ctx)) {
                    log("悬浮权限已授予，自动补挂")
                    permPoll = null
                    onShowMap(rect[0], rect[1], rect[0] + rect[2], rect[1] + rect[3])
                    return
                }
                UI.postDelayed(this, 1000)
            }
        }
        permPoll = r
        UI.postDelayed(r, 1500)
    }

    /* ==================== 还原 ==================== */

    /** 内容根 = decor.getChildAt(0)（payload findContent 同款；decor 留守不搬）。 */
    private fun findContent(act: Activity): View? {
        return try {
            val decor = act.window.decorView as? ViewGroup ?: return null
            if (decor.childCount == 0) return null
            decor.getChildAt(0)
        } catch (t: Throwable) {
            logW("findContent: ${t.message}")
            null
        }
    }

    private fun closeFloat() {
        if (container == null) {
            log("closemap: not floating")
            return
        }
        restoreContentToWindow()
    }

    /** 还原：内容 → 原父容器；容器摘除；全引用重置。 */
    private fun restoreContentToWindow() {
        val c = content
        val box = container
        val parent = originalParent
        content = null
        container = null
        floatingRect = null
        originalParent = null
        if (box != null) {
            try { appWindowManager().removeViewImmediate(box) } catch (_: Throwable) {}
            try { box.removeAllViews() } catch (_: Throwable) {}
        }
        if (c != null && parent != null) {
            // 原窗口回归：恢复可见+原背景（launch_background 由主题自己管）
            val actNow = topActivity
            if (actNow != null) {
                try { actNow.window.decorView.visibility = android.view.View.VISIBLE } catch (_: Throwable) {}
            }
            try {
                parent.addView(c, ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                if (c is io.flutter.embedding.android.FlutterView) {
                    try {
                        val eng = c.attachedFlutterEngine
                        if (eng != null) {
                            c.detachFromFlutterEngine()
                            c.attachToFlutterEngine(eng)
                        }
                    } catch (_: Throwable) {}
                }
                log("内容已还原回 Activity 窗口")
            } catch (t: Throwable) {
                logW("还原失败（重建兜底）: ${t.message}")
                try { topActivity?.recreate() } catch (_: Throwable) {}
            }
        } else if (c != null) {
            try { topActivity?.recreate() } catch (_: Throwable) {}
        }
    }

    private fun removeContainer() {
        val box = container
        content = null
        container = null
        floatingRect = null
        originalParent = null
        if (box != null) {
            try { appWindowManager().removeViewImmediate(box) } catch (_: Throwable) {}
            try { box.removeAllViews() } catch (_: Throwable) {}
        }
        log("host destroyed, reset")
    }

    /** payload 同款：0x120 flags（可聚焦）、TRANSLUCENT、2038/2002、TOP|START。 */
    private fun buildParams(x: Int, y: Int, w: Int, h: Int): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= 26)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY   // 2038
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE   // 2002
        return WindowManager.LayoutParams(w, h, type, FLAG_FLOAT, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
            title = "Md3Float"
        }
    }

    private fun appWindowManager(): WindowManager {
        val a = app!!
        return a.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    private fun reply(action: String) {
        try {
            val a = app ?: return
            // 隐式广播（不带包名）：海鸥 ACK 接收器 RECEIVER_EXPORTED 注册，星环同款
            a.sendBroadcast(Intent(action))
        } catch (_: Throwable) {}
    }
}
