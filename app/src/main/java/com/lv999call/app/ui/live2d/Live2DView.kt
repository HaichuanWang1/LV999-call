package com.lv999call.app.ui.live2d

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.SystemClock
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONObject

private const val TAG = "Live2DView"

/**
 * 加载超时（毫秒）
 *
 * 资源缺失或 JS 整体异常时可能永远收不到 ready/error 回调，
 * 若不设兜底，UI 会停在 LOADING → live2dActive 恒为 true →
 * 用户看到的是一片空白而不是静态头像。
 */
private const val LOAD_TIMEOUT_MS = 8_000L

/** Live2D 形象的加载状态 */
enum class Live2DStatus {
    /** 正在加载模型 */
    LOADING,

    /** 已就绪，可接受状态/口型指令 */
    READY,

    /** 加载失败（模型缺失等），UI 应回退到静态头像 */
    ERROR
}

/**
 * JS → Kotlin 的事件回调桥
 *
 * 注意：方法运行在 WebView 的 JS 线程，实现方需自行切回主线程。
 */
private class Live2DBridge(private val handler: (type: String, payload: String) -> Unit) {
    @JavascriptInterface
    fun onEvent(type: String, payload: String) {
        // 注意：属性名不能与该方法同名，否则 Kotlin 会把 onEvent(...) 解析为
        // 递归调用方法自身，导致 StackOverflowError（JS 回调全部丢失）。
        handler(type, payload)
    }
}

/**
 * Live2D 形象控制器
 *
 * 由 [rememberLive2DController] 创建，交给 [Live2DView] 绑定 WebView。
 * 外部只通过状态与数值驱动，不直接接触 WebView。
 */
@Stable
class Live2DController internal constructor() {

    internal var webView: WebView? = null

    /** 当前加载状态，可用于决定是否回退到静态头像 */
    var status: Live2DStatus by mutableStateOf(Live2DStatus.LOADING)
        internal set

    /** 最近一次错误信息（加载失败时非空） */
    var lastError: String? by mutableStateOf(null)
        internal set

    /** 模型支持的参数/动作信息，便于调试与后续扩展 */
    var modelInfo: JSONObject? by mutableStateOf(null)
        internal set

    /** 最近一次向 JS 推送口型的时间戳，用于限流 */
    private var lastMouthPushAt = 0L

    /** 最近一次推送的口型值，避免无意义的重复调用 */
    private var lastMouthValue = -1f

    // ------------------------- 对外指令 -------------------------

    /** 切换角色状态：idle / listening / thinking / speaking / ended */
    fun setState(state: String) {
        eval("window.L2D && window.L2D.setState('${state.jsEscape()}')")
    }

    /**
     * 推送音量（0f~1f）驱动口型
     *
     * 限流到约 60fps，并跳过变化极小的值，避免 evaluateJavascript 过载；
     * 平滑处理由 JS 侧负责。
     */
    fun setMouth(level: Float) {
        if (status != Live2DStatus.READY) return
        val v = level.coerceIn(0f, 1f)

        val now = SystemClock.uptimeMillis()
        if (now - lastMouthPushAt < 16) return
        if (kotlin.math.abs(v - lastMouthValue) < 0.01f) return

        lastMouthPushAt = now
        lastMouthValue = v
        eval("window.L2D && window.L2D.setMouth(${v.toString().take(6)})")
    }

    /** 是否允许口型驱动（关闭后角色闭嘴） */
    fun setMouthEnabled(enabled: Boolean) {
        eval("window.L2D && window.L2D.setMouthEnabled($enabled)")
    }

    /** 调整模型布局：模型高度占视口的比例、水平/垂直偏移 */
    fun setLayout(fillRatio: Float, offsetX: Float = 0f, offsetY: Float = 0f) {
        eval("window.L2D && window.L2D.setLayout({fillRatio:$fillRatio,offsetX:$offsetX,offsetY:$offsetY})")
    }

    /** 暂停渲染以省电（页面不可见 / 通话结束时调用） */
    fun setPaused(paused: Boolean) {
        eval("window.L2D && window.L2D.setPaused($paused)")
    }

    /** 播放指定动作组 */
    fun playMotion(group: String, index: Int? = null) {
        val idx = index?.toString() ?: "undefined"
        eval("window.L2D && window.L2D.playMotion('${group.jsEscape()}', $idx)")
    }

    /**
     * 播放变身过场
     *
     * 动作来自模型自带的 Transform_1/2（作者原文件是 Loop 的，副本才是一次性），
     * 由 tools/live2d_make_idle.py 生成并注册成 TransformOnce 组。
     *
     * @param phase "full"（进入→还原，约 4.7s）/ "in" / "out"（只还原，约 2.3s）
     *
     * 动作组不存在时 JS 侧只记一条警告并返回 false，不会抛异常。
     */
    fun playTransform(phase: String = "full") {
        if (status != Live2DStatus.READY) return
        eval("window.L2D && window.L2D.playTransform('${phase.jsEscape()}')")
    }

    /**
     * 情绪表情（传模型 Expressions[].Name 的真实值，如 `"03生气"`；null 复位）。
     *
     * 与 [setState] 的关系：JS 侧把它记为「情绪覆盖层」，状态切换时仍然优先生效
     * （否则 thinking→speaking 的状态切换会把刚触发的情绪表情冲掉），
     * 只有显式传 null 才回落到当前状态的默认表情。
     */
    fun setExpression(name: String?) {
        val arg = name?.let { "'${it.jsEscape()}'" } ?: "null"
        eval("window.L2D && window.L2D.setExpression($arg)")
    }

    // ------------------------- 内部实现 -------------------------

    private fun eval(js: String) {
        val wv = webView ?: return
        if (status != Live2DStatus.READY) return
        try {
            wv.evaluateJavascript(js, null)
        } catch (e: Exception) {
            Log.w(TAG, "evaluateJavascript 失败: ${e.message}")
        }
    }

    /** 超时兜底：仍处于 LOADING 则判定失败，让 UI 回退静态头像 */
    internal fun markLoadTimeout() {
        if (status == Live2DStatus.LOADING) {
            status = Live2DStatus.ERROR
            lastError = "Live2D 加载超时（资源缺失或 WebView 异常）"
            Log.w(TAG, lastError!!)
        }
    }

    internal fun handleEvent(type: String, payload: String) {
        when (type) {
            "ready" -> {
                status = Live2DStatus.READY
                lastError = null
                modelInfo = runCatching { JSONObject(payload) }.getOrNull()
                Log.i(TAG, "Live2D 就绪: $payload")
            }

            "error" -> {
                status = Live2DStatus.ERROR
                lastError = runCatching { JSONObject(payload).optString("message") }.getOrNull() ?: payload
                Log.e(TAG, "Live2D 错误: $lastError")
            }

            "info" -> {
                modelInfo = runCatching { JSONObject(payload) }.getOrNull()
                Log.i(TAG, "Live2D 信息: $payload")
            }
        }
    }

    /**
     * 释放 WebView 与 JS 侧模型
     *
     * 注意：必须先取出引用再置空，且销毁动作要放在同一个方法内完成，
     * 否则调用方拿到的 webView 已是 null，destroy() 会被静默跳过（内存泄漏）。
     */
    internal fun release() {
        val wv = webView ?: return
        webView = null
        status = Live2DStatus.LOADING

        // 先让 JS 侧释放 PIXI / Live2D 资源
        try {
            wv.evaluateJavascript("window.L2D && window.L2D.dispose()", null)
        } catch (_: Exception) {
        }

        // 再销毁 WebView 本体
        try {
            wv.stopLoading()
            wv.loadUrl("about:blank")
            wv.destroy()
        } catch (e: Exception) {
            Log.w(TAG, "WebView 释放异常: ${e.message}")
        }
    }
}

/** JS 字符串字面量转义，防止注入 */
private fun String.jsEscape(): String =
    replace("\\", "\\\\").replace("'", "\'").replace("\n", "\n").replace("\r", "")

/**
 * 创建并配置承载 Live2D 的 WebView
 */
@SuppressLint("SetJavaScriptEnabled")
private fun createWebView(
    context: android.content.Context,
    controller: Live2DController,
    modelPath: String? = null
): WebView {
    return WebView(context).apply {
        // 透明背景，才能叠在 Compose 渐变之上。
        //
        // 切勿对 WebView 调用 setLayerType(LAYER_TYPE_HARDWARE)：
        // WebView 走自己的 Chromium 合成管线（RenderThread + Surface），
        // 外层硬套硬件层会让内容完全无法上屏 —— 表现为视图层级里
        // WebView 全屏可见、页面内部渲染正常（canvas 有像素），
        // 但屏幕上什么都看不到。
        setBackgroundColor(Color.TRANSPARENT)

        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mediaPlaybackRequiresUserGesture = false
            // 所有资源都走 shouldInterceptRequest，无需开放文件/内容访问
            allowFileAccess = false
            allowContentAccess = false
            // 关闭缩放与文本适配，避免画布被二次缩放
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = false
        }

        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        overScrollMode = android.view.View.OVER_SCROLL_NEVER

        // JS → Kotlin：回调在 JS 线程，切回主线程再改 Compose 状态
        addJavascriptInterface(
            Live2DBridge { type, payload -> post { controller.handleEvent(type, payload) } },
            "AndroidBridge"
        )

        webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                return Live2DAssetLoader.intercept(context.applicationContext, request.url)
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: android.webkit.WebResourceError
            ) {
                if (request.isForMainFrame) {
                    controller.status = Live2DStatus.ERROR
                    controller.lastError = "WebView 加载失败: ${error.description}"
                    Log.e(TAG, "主框架加载失败: ${error.description}")
                }
            }
        }

        webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                // 把页面内日志转出到 Logcat，便于排查模型/渲染问题
                Log.d(TAG, "[web] ${msg.message()} (${msg.sourceId()}:${msg.lineNumber()})")
                return true
            }
        }

        loadUrl(Live2DAssetLoader.indexUrl(modelPath))
    }
}
/** 记住一个 Live2D 控制器，随 Composable 生命周期自动释放 */
@Composable
fun rememberLive2DController(): Live2DController = remember { Live2DController() }

/**
 * Live2D 形象视图
 *
 * 用法：
 * ```
 * val l2d = rememberLive2DController()
 * LaunchedEffect(callState) { l2d.setState(callState.toLive2DState()) }
 * LaunchedEffect(audioLevel) { l2d.setMouth(audioLevel) }
 * Live2DView(controller = l2d, modifier = Modifier.fillMaxSize())
 * ```
 *
 * @param controller 由 [rememberLive2DController] 创建
 * @param modelPath 模型相对 assets/live2d 的路径，如
 *        `models/haru/haru_greeter_t03.model3.json`；传 null 用 JS 默认值
 * @param paused 为 true 时暂停渲染以省电（例如通话结束）
 * @param onStatusChange 状态变化回调，可据此回退到静态头像
 */
@Composable
fun Live2DView(
    controller: Live2DController,
    modifier: Modifier = Modifier,
    modelPath: String? = null,
    paused: Boolean = false,
    onStatusChange: (Live2DStatus) -> Unit = {}
) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            createWebView(ctx, controller, modelPath).also { controller.webView = it }
        }
    )

    // 状态变化向上汇报
    LaunchedEffect(controller.status) {
        onStatusChange(controller.status)
    }

    // 加载超时兜底：避免资源缺失时停在 LOADING 导致空白
    LaunchedEffect(controller) {
        kotlinx.coroutines.delay(LOAD_TIMEOUT_MS)
        controller.markLoadTimeout()
    }

    // 暂停 / 恢复渲染
    LaunchedEffect(paused, controller.status) {
        if (controller.status == Live2DStatus.READY) {
            controller.setPaused(paused)
        }
    }

    // 随 Composable 销毁释放 WebView（含 JS 侧模型）
    DisposableEffect(Unit) {
        onDispose { controller.release() }
    }
}
