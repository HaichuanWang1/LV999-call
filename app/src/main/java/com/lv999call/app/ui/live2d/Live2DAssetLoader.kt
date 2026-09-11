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
                // 同源资源，允许缓存，减少重复读取
                responseHeaders = mapOf("Cache-Control" to "max-age=3600")
            }
        } catch (e: IOException) {
            // 资源不存在：返回 404 响应，避免 WebView 报未知错误
            WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), null)
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
