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
import io.flutter.embedding.android.FlutterView
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.engine.FlutterEngineCache

/**
 * 车机悬浮（原生实现——有源码就不摘窗）：
 *
 * 旧方案（payload 摘窗法）在 Flutter 上必然黑屏：FlutterView 默认 SurfaceView
 * 直合成（独立 SurfaceControl），跨窗口摘挂 = Surface 销毁重建竞态。
 *
 * 本方案【不摘任何 View】：
 *  · 悬浮窗里新建一个 RenderMode.texture 的 FlutterView（TextureView 走视图层级
 *    合成，跨窗口可移植）
 *  · showmap：引擎从原 FlutterView 摘下，绑到悬浮 FlutterView（官方 API，
 *    Activity 重建复用引擎就是这条路径）；宿主 moveTaskToBack 退场
 *  · closemap / 用户点开应用（onActivityResumed）：引擎绑回原 FlutterView，
 *    悬浮窗摘除
 *  · 同一引擎同一 isolate：播放状态/UI 与主界面天然同步
 *  · 宿主退后台后 Activity 生命周期会把 Dart 压成 paused → 生命周期事件后强制
 *    lifecycleChannel.appIsResumed() 反压制，Dart 持续出帧
 *
 * 协议不变（星环悬浮补丁）：showmap/closemap，w/h=右下角坐标，floated/noview 隐式 ACK。
 */
object CarFloatHook {

    private const val TAG = "Md3CarFloat"
    private const val ENGINE_ID = "md3music_engine"

    const val ACTION_SHOW = "com.autonavi.plus.showmap"
    const val ACTION_CLOSE = "com.autonavi.plus.closemap"
    const val ACTION_FLOATED = "cn.kuwo.plus.floated"
    const val ACTION_NOVIEW = "cn.kuwo.plus.noview"

    private const val FLAG_FLOAT = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL   // 0x120，可聚焦

    private val main = Handler(Looper.getMainLooper())

    @Volatile private var app: Application? = null
    @Volatile private var topActivity: Activity? = null
    @Volatile private var container: FrameLayout? = null
    @Volatile private var overlayView: FlutterView? = null
    @Volatile private var originalView: FlutterView? = null
    @Volatile private var engine: FlutterEngine? = null
    @Volatile private var floatingRect: IntArray? = null

    @JvmStatic
    fun show(x: Int, y: Int, right: Int, bottom: Int) {
        onShowMap(x, y, right, bottom)
    }

    @JvmStatic
    fun close() {
        closeFloat()
    }

    @JvmStatic
    fun install(application: Application) {
        app = application
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                topActivity = activity
                // 用户点开应用全屏使用 → 引擎换绑回原 View + 拆悬浮（正确语义，非还原风暴）
                if (container != null) closeFloat()
            }
            override fun onActivityPaused(activity: Activity) = counterForceResume()
            override fun onActivityStopped(activity: Activity) = counterForceResume()
            override fun onActivityDestroyed(activity: Activity) {
                if (topActivity === activity) topActivity = null
                if (container != null) teardownOverlay()
            }
            override fun onActivityCreated(a: Activity, s: android.os.Bundle?) = Unit
            override fun onActivityStarted(a: Activity) = Unit
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
            Log.i(TAG, "车机悬浮协议已挂（原生 texture 引擎换绑版）")
        } catch (t: Throwable) {
            Log.w(TAG, "协议注册失败: ${t.message}")
        }
        try {
            CarFloatKeepService.ensure(application)
        } catch (t: Throwable) {
            Log.w(TAG, "保活服务拉起失败: ${t.message}")
        }
    }

    private fun onShowMap(x: Int, y: Int, right: Int, bottom: Int) {
        val a = app ?: return reply(ACTION_NOVIEW)

        if (!canDrawOverlays(a)) {
            Log.i(TAG, "showmap：无悬浮权限，跳授权页")
            reply(ACTION_NOVIEW)
            try {
                a.startActivity(Intent(
                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:${a.packageName}")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Throwable) {}
            return
        }

        val w = right - x
        val h = bottom - y
        if (w <= 0 || h <= 0) return reply(ACTION_NOVIEW)

        // 已浮着：同矩形幂等；矩形变化 → updateViewLayout（内容 MATCH_PARENT 自动跟随）
        if (container != null && overlayView != null) {
            val r = floatingRect
            if (r != null && r[0] == x && r[1] == y && r[2] == w && r[3] == h) {
                reply(ACTION_FLOATED)
                return
            }
            try {
                wm().updateViewLayout(container, buildParams(x, y, w, h))
                overlayView!!.requestLayout()
                floatingRect = intArrayOf(x, y, w, h)
                reply(ACTION_FLOATED)
                Log.i(TAG, "悬浮窗重定位 rect($x,$y,$w,$h)")
            } catch (t: Throwable) {
                Log.w(TAG, "重定位失败: ${t.message}")
                reply(ACTION_NOVIEW)
            }
            return
        }

        val act = topActivity
        if (act == null || act.window == null) {
            Log.i(TAG, "showmap：无前台 Activity")
            reply(ACTION_NOVIEW)
            return
        }

        try {
            val orig = findFlutterView(act.window.decorView as? ViewGroup)
            val eng = orig?.attachedFlutterEngine
                ?: FlutterEngineCache.getInstance().get(ENGINE_ID)
            if (eng == null) {
                Log.i(TAG, "showFloat：引擎不可用")
                reply(ACTION_NOVIEW)
                return
            }

            // 1) 引擎从原 View 摘下（原 View 留在原窗口不动——Activity 重建路径同款）
            try { orig?.detachFromFlutterEngine() } catch (_: Throwable) {}
            originalView = orig
            engine = eng

            // 2) 新建 texture+transparent 模式悬浮 FlutterView（视图层级合成，跨窗口可移植）
            val view = FlutterView(act,
                io.flutter.embedding.android.RenderMode.texture,
                io.flutter.embedding.android.TransparencyMode.transparent)
            view.attachToFlutterEngine(eng)

            // 3) 深色容器 + 挂悬浮窗
            val box = FrameLayout(act)
            box.background = ColorDrawable(Color.parseColor("#0E1114"))
            box.addView(view, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            wm().addView(box, buildParams(x, y, w, h))
            container = box
            overlayView = view
            floatingRect = intArrayOf(x, y, w, h)

            // 4) 宿主退后台（黑壳不出现在屏幕上）+ 反压制 Dart 生命周期
            try {
                act.moveTaskToBack(true)
                Log.i(TAG, "宿主已退后台（pushHostBack）")
            } catch (_: Throwable) {}
            forceResume()

            reply(ACTION_FLOATED)
            Log.i(TAG, "悬浮成功 rect($x,$y,$w,$h)，已回 ACK（原生 texture 版）")
        } catch (t: Throwable) {
            Log.w(TAG, "悬浮失败: ${t.message}")
            teardownOverlay()
            reply(ACTION_NOVIEW)
        }
    }

    private fun closeFloat() {
        if (container == null) {
            Log.i(TAG, "closemap: not floating")
            return
        }
        // 引擎绑回原 View（用户全屏打开应用/海鸥收窗），悬浮窗摘除
        teardownOverlay()
        Log.i(TAG, "悬浮已收（引擎已换绑回原 View）")
    }

    private fun teardownOverlay() {
        val view = overlayView
        val box = container
        val eng = engine
        overlayView = null
        container = null
        floatingRect = null
        if (view != null) {
            try { view.detachFromFlutterEngine() } catch (_: Throwable) {}
        }
        if (box != null) {
            try { wm().removeViewImmediate(box) } catch (_: Throwable) {}
            try { box.removeAllViews() } catch (_: Throwable) {}
        }
        val orig = originalView
        if (eng != null && orig != null) {
            try { orig.attachToFlutterEngine(eng) } catch (t: Throwable) {
                Log.w(TAG, "引擎换绑回原 View 失败: ${t.message}")
            }
        }
        originalView = null
        engine = null
    }

    /** 宿主退后台后 Activity 生命周期会把 Dart 压 paused → 强制 resumed 反压制。 */
    private fun counterForceResume() {
        if (container == null) return
        main.postDelayed({
            if (container != null) forceResume()
        }, 250)
    }

    private fun forceResume() {
        val eng = engine ?: return
        try {
            eng.lifecycleChannel.appIsResumed()
        } catch (t: Throwable) {
            Log.w(TAG, "forceResume: ${t.message}")
        }
    }

    private fun findFlutterView(root: ViewGroup?): FlutterView? {
        if (root == null) return null
        if (root is FlutterView) return root
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i)
            if (child is FlutterView) return child
            if (child is ViewGroup) findFlutterView(child)?.let { return it }
        }
        return null
    }

    private fun buildParams(x: Int, y: Int, w: Int, h: Int): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= 26)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        return WindowManager.LayoutParams(w, h, type, FLAG_FLOAT, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
            title = "Md3Float"
            // 实测坑（HyperOS 竖屏 display + 横屏 app）：WM 默认 fitTypes=navigationBars|
            // captionBar|systemOverlays 强制窗口适配系统栏并重布局 → mAttrs 的 x/y/w/h 被
            // 忽略，窗口铺成「可用区全宽横条」（frame=[0,104][1080,938]），盖住桌面中段
            // 全部手势区 → 海鸥左右滑翻页/长按把手全失效。屏蔽一切 fit + 短边刘海，矩形说了算。
            fitInsetsTypes = 0
            fitInsetsSides = 0
            fitInsetsIgnoringVisibility = 0
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    private fun wm(): WindowManager =
        app!!.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private fun canDrawOverlays(ctx: Context): Boolean =
        try { android.provider.Settings.canDrawOverlays(ctx) } catch (_: Throwable) { false }

    private fun reply(action: String) {
        try {
            app?.sendBroadcast(Intent(action))
        } catch (_: Throwable) {}
    }
}
