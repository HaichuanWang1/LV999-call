package com.lv999call.app.ui.live2d

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceResponse
import java.io.IOException

/**
 * Live2D 资源的 WebView 拦截加载器
 *
 * 背景：WebView 直接以 file:// 打开 assets 时，页面内的 XMLHttpRequest
 * 会被同源策略拦截（pixi-live2d-display 正是用 XHR 加载 .moc3 / 贴图）。
 *
 * 方案：把页面挂在虚拟域名 https://appassets.androidplatform.net 下，
 * 由 shouldInterceptRequest 直接回源到 assets，等价于同源的 https 环境，
 * 既绕开 file:// 限制，又不需要开放 allowUniversalAccessFromFileURLs 等不安全开关。
 */
internal object Live2DAssetLoader {

    /** 虚拟域名（不解析，全部由拦截器就地返回） */
    const val HOST = "appassets.androidplatform.net"

    /** assets 中 Live2D 资源根目录 */
    private const val ASSET_ROOT = "live2d"

    /** 虚拟目录前缀 */
    private const val URL_PREFIX = "/assets/$ASSET_ROOT/"

    /** 入口页面地址 */
    val INDEX_URL: String = "https://$HOST$URL_PREFIX" + "index.html"

    /**
     * 带形象参数的入口地址。
     *
     * 通过 `?model=` 指定模型、`?profile=` 指定 bridge.js 里的形象参数档位
     * （待机通道 / 布局 / 呼吸 / 是否变身等），换模型换角色都无需改 JS。
     *
     * @param modelPath 模型相对 assets/live2d 的路径；空则用 profile 里的默认值
     * @param profileId bridge.js `PROFILES` 的键；空则回落银狼
     */
    fun indexUrl(modelPath: String? = null, profileId: String? = null): String {
        val params = mutableListOf<String>()
        if (!modelPath.isNullOrBlank()) params += "model=" + Uri.encode(modelPath)
        if (!profileId.isNullOrBlank()) params += "profile=" + Uri.encode(profileId)
        return if (params.isEmpty()) INDEX_URL else "$INDEX_URL?" + params.joinToString("&")
    }

    /**
     * 命中虚拟域名时返回 assets 内容，否则返回 null 交由系统处理
     */
    fun intercept(context: Context, url: Uri): WebResourceResponse? {
        if (url.host != HOST) return null

        val path = url.path ?: return null
        if (!path.startsWith(URL_PREFIX)) return null

        // /assets/live2d/models/haru/x.moc3  ->  models/haru/x.moc3
        val relative = path.substring(URL_PREFIX.length)
        if (relative.isEmpty()) return null

        return openAsset(context, relative)
    }

    private fun openAsset(context: Context, relative: String): WebResourceResponse? {
        return try {
            // 防目录穿越
            if (relative.contains("..")) return null
            val stream = context.assets.open("$ASSET_ROOT/$relative")
            WebResourceResponse(mimeOf(relative), encodingOf(relative), stream).apply {
                // 禁用缓存：资源随 APK 版本变化，但 URL 不变，
                // 若命中 WebView 缓存会导致升级后仍加载旧模型。
                responseHeaders = mapOf("Cache-Control" to "no-store")
            }
        } catch (e: IOException) {
            // 资源不存在：返回 404（空流而非 null，部分 WebView 版本不接受 null）
            WebResourceResponse(
                "text/plain", "utf-8", 404, "Not Found", emptyMap(),
                java.io.ByteArrayInputStream(ByteArray(0))
            )
        }
    }

    private fun mimeOf(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
        "html", "htm" -> "text/html"
        "js", "mjs" -> "application/javascript"
        "json" -> "application/json"
        "css" -> "text/css"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "svg" -> "image/svg+xml"
        "mp3" -> "audio/mpeg"
        "wav" -> "audio/wav"
        "ogg" -> "audio/ogg"
        // .moc3 / .motion3.json 之外的二进制
        else -> "application/octet-stream"
    }

    /** 文本类资源需要显式编码，二进制传 null */
    private fun encodingOf(path: String): String? =
        if (mimeOf(path).startsWith("text/") || mimeOf(path) == "application/json" ||
            mimeOf(path) == "application/javascript"
        ) "utf-8" else null
}
