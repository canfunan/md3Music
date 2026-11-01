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
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout

/**
 * 车机悬浮协议（逐字对齐星环注入器 payload 的 FloatWindow 权威实现，
 * 反编译 assets/payload.dex → cn.kuwo.kwmusiccar.FloatWindow 逐条比对）：
 *
 *  · 摘【decor.getChildAt(0)】内容根 View —— decor 留在 Activity 窗口当空壳，
 *    不破坏 Activity 的窗口关系链（直接搬 decor = "already been added" 实锤炸点）
 *  · 自建 FrameLayout 容器挂 WindowManager（深色底），内容 remove 后塞进容器
 *  · flags = 0x120（LAYOUT_IN_SCREEN | NOT_TOUCH_MODAL，**可聚焦** —— 输入法可用，
 *    QQ/网易云注入版同款）；type = 2038（≥O）/ 2002；format = TRANSLUCENT
 *  · 内容 setFitsSystemWindows(false) + 零 padding（去掉系统栏 inset 残留）
 *  · closemap：内容 remove → 塞回【原父容器】（挂浮时记录的原 parent）
 *  · resize：容器 updateViewLayout，内容 MATCH_PARENT 跟随（Compose 自动重排，
 *    天然解决「拉伸掉挂」）
 *  · 应用回前台（resume）→ 自动还原内容到窗口（用户全屏打开应用时的自然行为）
 *  · 宿主销毁 → 容器摘除 + 全引用重置
 */
object CarFloatHook {

    private const val TAG = "Md3CarFloat"

    const val ACTION_SHOW = "com.autonavi.plus.showmap"
    const val ACTION_CLOSE = "com.autonavi.plus.closemap"
    const val ACTION_FLOATED = "cn.kuwo.plus.floated"
    const val ACTION_NOVIEW = "cn.kuwo.plus.noview"

    private const val FLAG_FLOAT = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL   // 0x120，payload 同款

    /** 服务直投入口（CarFloatKeepService onStartCommand 调）：binder 解冻 + 主线程挂窗。 */
    fun show(x: Int, y: Int, right: Int, bottom: Int) {
        onShowMap(x, y, right, bottom)
    }

    /** 服务直投关闭入口（extra close=1）。 */
    fun close() {
        closeFloat()
    }


    @Volatile private var app: Application? = null
    @Volatile private var topActivity: Activity? = null
    @Volatile private var container: FrameLayout? = null
    @Volatile private var content: View? = null
    @Volatile private var originalParent: ViewGroup? = null
    @Volatile private var floatingRect: IntArray? = null

    @JvmStatic
    fun install(application: Application) {
        app = application
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                topActivity = activity
                // 应用回前台 → 内容还原回窗口（全屏使用）；海鸥随后重发 showmap 再浮
                if (container != null) restoreContentToWindow()
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
            override fun onActivityPaused(a: Activity) = Unit
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
                        ACTION_CLOSE -> closeFloat()
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "onReceive: ${t.message}")
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
            Log.i(TAG, "车机悬浮协议已挂（showmap/floated）")
        } catch (t: Throwable) {
            Log.w(TAG, "协议注册失败: ${t.message}")
        }
        // 无 root 保活：FGS 载体进程冻结引擎不碰（greezer 对裸进程秒冻 = showmap 被 SKIPPED）
        try {
            CarFloatKeepService.ensure(application)
        } catch (t: Throwable) {
            Log.w(TAG, "保活服务拉起失败: ${t.message}")
        }
    }

    /** showmap。w/h = 右下角坐标语义（海鸥按猴哥协议发），先转宽高。 */
    private fun onShowMap(x: Int, y: Int, right: Int, bottom: Int) {
        val a = app ?: return reply(ACTION_NOVIEW)

        // 无悬浮权限（普通用户首次）：跳系统授权页点一次「允许」，之后全自动
        if (!canDrawOverlays(a)) {
            Log.i(TAG, "showmap：无悬浮权限，跳授权页")
            reply(ACTION_NOVIEW)
            try {
                a.startActivity(Intent(
                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:${a.packageName}")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (t: Throwable) {
                Log.w(TAG, "跳授权页失败: ${t.message}")
            }
            return
        }

        val w = right - x
        val h = bottom - y
        if (w <= 0 || h <= 0) return reply(ACTION_NOVIEW)

        val act = topActivity
        val cur = content
        // 已浮着：同矩形幂等（海鸥重挂会重发），矩形变化 → 容器直接 updateViewLayout
        if (container != null && cur != null) {
            val r = floatingRect
            if (r != null && r[0] == x && r[1] == y && r[2] == w && r[3] == h) {
                reply(ACTION_FLOATED)
                return
            }
            try {
                appWindowManager().updateViewLayout(container, buildParams(x, y, w, h))
                // 内容全幅跟随画框：requestLayout 让 Compose 按新尺寸重组
                //（width>height 时走双栏横版，MeloX 页面自身响应窗口尺寸）
                cur.requestLayout()
                floatingRect = intArrayOf(x, y, w, h)
                reply(ACTION_FLOATED)
                Log.i(TAG, "悬浮窗重定位 rect($x,$y,$w,$h)")
            } catch (t: Throwable) {
                Log.w(TAG, "重定位失败: ${t.message}")
                reply(ACTION_NOVIEW)
            }
            return
        }
        if (act == null || act.window == null) {
            Log.i(TAG, "showmap：无前台 Activity，回 noview")
            reply(ACTION_NOVIEW)
            return
        }

        try {
            val c = findContent(act) ?: run {
                Log.i(TAG, "showFloat: content not ready")
                reply(ACTION_NOVIEW)
                return
            }
            val parent = c.parent as? ViewGroup
            if (parent == null) {
                reply(ACTION_NOVIEW)
                return
            }
            // 1) 内容从原父容器摘出
            parent.removeView(c)
            originalParent = parent
            // 2) 自建深色容器（QQ 车机风格底色），内容【全幅】跟随画框（payload 同款
            //    MATCH_PARENT）—— 画框多大界面多大；窗口横 = Compose 自动双栏横版
            val box = FrameLayout(act)
            box.background = ColorDrawable(Color.parseColor("#0E1114"))
            box.addView(c, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            // payload 收尾：去掉系统栏 inset 残留（悬浮矩形里内容要满幅）
            c.fitsSystemWindows = false
            c.setPadding(0, 0, 0, 0)
            // Flutter 黑屏对抗（MD3 实测：摘窗后悬浮窗黑）——三层：
            //  a. 官方 API 重挂引擎：FlutterView detach 时引擎停渲染（原 Surface 随原窗口销毁），
            //     跨窗口 attach 不自动重启渲染（尤其 MD3 的引擎复用路径 attachToEngine 不重跑）。
            //     detach + attach 强制 FlutterRenderer 拿新窗口的新 Surface。
            //  b. 钉回不透明背景（应用为缩小动画设过 TRANSPARENT，表面没画出来时整片黑）。
            //  c. post 延迟强制重绘兜底。
            if (c is io.flutter.embedding.android.FlutterView) {
                try {
                    val eng = c.attachedFlutterEngine
                    if (eng != null) {
                        c.detachFromFlutterEngine()
                        c.attachToFlutterEngine(eng)
                        Log.i(TAG, "FlutterView 引擎已重挂（跨窗口 Surface 重建）")
                    }
                } catch (t: Throwable) { Log.w(TAG, "引擎重挂失败: ${t.message}") }
                try { c.setBackgroundColor(Color.parseColor("#0E1114")) } catch (_: Throwable) {}
            }
            c.post {
                c.invalidate()
                c.requestLayout()
                c.postDelayed({ c.invalidate() }, 120)
            }
            // 3) 容器挂系统悬浮窗（payload 同款参数：可聚焦，输入法可用）
            appWindowManager().addView(box, buildParams(x, y, w, h))
            container = box
            content = c
            floatingRect = intArrayOf(x, y, w, h)
            reply(ACTION_FLOATED)
            Log.i(TAG, "悬浮成功 rect($x,$y,$w,$h)，已回 ACK")
        } catch (t: Throwable) {
            Log.w(TAG, "悬浮失败: ${t.message}")
            // 失败把内容还回去，应用别黑掉
            restoreContentToWindow()
            reply(ACTION_NOVIEW)
        }
    }

    /** 内容根 = decor.getChildAt(0)（payload findContent 同款；decor 留守不搬）。 */
    private fun findContent(act: Activity): View? {
        return try {
            val decor = act.window.decorView as? ViewGroup ?: return null
            if (decor.childCount == 0) return null
            decor.getChildAt(0)
        } catch (t: Throwable) {
            Log.w(TAG, "findContent: ${t.message}")
            null
        }
    }

    private fun closeFloat() {
        if (container == null) {
            Log.i(TAG, "closemap: not floating")
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
                Log.i(TAG, "内容已还原回 Activity 窗口")
            } catch (t: Throwable) {
                Log.w(TAG, "还原失败（重建兜底）: ${t.message}")
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
        Log.i(TAG, "host destroyed, reset")
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

    private fun canDrawOverlays(ctx: Context): Boolean =
        try { android.provider.Settings.canDrawOverlays(ctx) } catch (_: Throwable) { false }

    private fun reply(action: String) {
        try {
            val a = app ?: return
            // 隐式广播（不带包名）：海鸥 ACK 接收器 RECEIVER_EXPORTED 注册，星环同款
            a.sendBroadcast(Intent(action))
        } catch (_: Throwable) {}
    }
}
