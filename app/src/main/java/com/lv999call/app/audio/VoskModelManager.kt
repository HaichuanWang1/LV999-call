package com.lv999call.app.audio

import android.content.Context
import android.util.Log
import com.lv999call.app.domain.model.ApiConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Vosk 模型管理器
 * 支持从 assets 解压打包模型 和 运行时下载模型
 */
class VoskModelManager(private val context: Context) {

    companion object {
        private const val TAG = "VoskModelManager"
        private const val MODELS_DIR = "vosk_models"
        private const val ASSETS_MODELS_DIR = "vosk-models"

        /**
         * 解压用的缓冲区。
         *
         * 模型有 68 MB，`copyTo` 默认的 8 KB 缓冲区要跑八千多轮；
         * 64 KB 能把首次解压的时间砍掉一截，代价只有几十 KB 内存。
         */
        private const val COPY_BUFFER_BYTES = 64 * 1024

        /** 预定义模型列表 */
        val AVAILABLE_MODELS = listOf(
            VoskModel(
                ApiConfig.DEFAULT_VOSK_MODEL_ID,
                "中文（小）",
                "zh",
                "~50MB",
                "https://alphacephei.com/vosk/models/vosk-model-small-cn-0.22.zip"
            ),
        )
    }

    /**
     * 解压锁。
     *
     * 解压是"整目录复制"，两个调用方同时进来会互相覆盖出半个模型
     * （通话路径与设置页可能同时触发）。这里把整段串行化。
     */
    private val extractLock = Mutex()

    data class VoskModel(
        val id: String,
        val displayName: String,
        val lang: String,
        val size: String,
        val downloadUrl: String
    )

    private fun getModelsDir(): File {
        val dir = File(context.filesDir, MODELS_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getModelPath(modelId: String): File = File(getModelsDir(), modelId)

    /** 模型是否已经解压到内部存储（解压过就不用再解一次） */
    fun isModelExtracted(modelId: String): Boolean {
        val modelDir = getModelPath(modelId)
        return modelDir.exists() && modelDir.isDirectory &&
            modelDir.listFiles()?.isNotEmpty() == true
    }

    fun isModelAvailable(modelId: String): Boolean {
        // 检查是否已解压到内部存储
        if (isModelExtracted(modelId)) return true
        // 检查 assets 中是否有该模型
        return try {
            val assetPath = "$ASSETS_MODELS_DIR/$modelId"
            context.assets.list(assetPath)?.isNotEmpty() == true
        } catch (_: Exception) {
            false
        }
    }

    fun getAvailableModels(): List<VoskModel> {
        return AVAILABLE_MODELS.filter { isModelAvailable(it.id) }
    }

    /**
     * 确保模型可用 — 优先从 assets 解压，否则从内部存储加载
     *
     * @param onProgress 解压进度 0f..1f。**已经在内部存储时不会回调**（无事可报），
     *   调用方若要区分"解压中 / 载入中"，看 [isModelExtracted] 即可。
     * @return 模型路径，失败返回 null
     */
    suspend fun ensureModelReady(
        modelId: String,
        onProgress: (Float) -> Unit = {}
    ): String? = withContext(Dispatchers.IO) {
        if (isModelExtracted(modelId)) {
            return@withContext getModelPath(modelId).absolutePath
        }

        extractLock.withLock {
            // 拿到锁之后再确认一次：等锁的这段时间里，前一个持锁者可能已经解压完了
            if (isModelExtracted(modelId)) {
                return@withContext getModelPath(modelId).absolutePath
            }
            if (extractFromAssets(modelId, onProgress)) {
                return@withContext getModelPath(modelId).absolutePath
            }
        }

        Log.e(TAG, "模型不可用: $modelId")
        null
    }

    /**
     * 从 assets 解压模型到内部存储（仅首次）
     *
     * @param onProgress 0f..1f，按已复制字节数 / 模型总字节数计算
     */
    private fun extractFromAssets(modelId: String, onProgress: (Float) -> Unit): Boolean {
        val assetPath = "$ASSETS_MODELS_DIR/$modelId"
        val targetDir = getModelPath(modelId)

        return try {
            val files = context.assets.list(assetPath)
            if (files.isNullOrEmpty()) {
                Log.d(TAG, "assets中无模型: $assetPath")
                return false
            }

            val total = assetTotalBytes(assetPath).coerceAtLeast(1L)
            var copied = 0L
            // 进度回调发生在复制循环里，逐块上报会把主线程刷爆；
            // 每变化 1% 报一次，进度条看起来已经是连续的
            var lastPercent = -1

            targetDir.mkdirs()
            Log.d(TAG, "从assets解压模型: $modelId (共 ${total / 1024 / 1024} MB)")
            onProgress(0f)

            extractAssetDir(assetPath, targetDir) { bytes ->
                copied += bytes
                val percent = (copied * 100 / total).toInt()
                if (percent != lastPercent) {
                    lastPercent = percent
                    onProgress((copied.toFloat() / total).coerceIn(0f, 1f))
                }
            }

            Log.d(TAG, "模型解压完成: ${targetDir.absolutePath}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "assets解压失败: ${e.message}")
            targetDir.deleteRecursively()
            false
        }
    }

    /**
     * 递归累加一个 assets 目录下所有文件的字节数 —— 解压进度的分母。
     *
     * `assets.list()` 对**文件**和**空目录**都返回空数组，靠 `open()` 抛不抛异常区分。
     */
    private fun assetTotalBytes(assetPath: String): Long {
        val entries = context.assets.list(assetPath) ?: return 0L
        if (entries.isEmpty()) {
            return try {
                context.assets.open(assetPath).use { it.available().toLong() }
            } catch (_: Exception) {
                0L
            }
        }
        var sum = 0L
        for (entry in entries) sum += assetTotalBytes("$assetPath/$entry")
        return sum
    }

    /**
     * 递归解压 assets 目录
     *
     * @param onBytes 每写完一个文件回调一次，参数是这次写入的字节数
     */
    private fun extractAssetDir(assetPath: String, targetDir: File, onBytes: (Long) -> Unit) {
        val entries = context.assets.list(assetPath) ?: return

        if (entries.isEmpty()) {
            // 是文件，复制
            context.assets.open(assetPath).use { input ->
                FileOutputStream(File(targetDir, assetPath.substringAfterLast('/'))).use { output ->
                    onBytes(input.copyTo(output, COPY_BUFFER_BYTES))
                }
            }
        } else {
            // 是目录，递归
            for (entry in entries) {
                val childAssetPath = "$assetPath/$entry"
                val childTargetDir = File(targetDir, entry)
                val subEntries = context.assets.list(childAssetPath)

                if (subEntries.isNullOrEmpty()) {
                    // 文件
                    context.assets.open(childAssetPath).use { input ->
                        if (!targetDir.exists()) targetDir.mkdirs()
                        FileOutputStream(File(targetDir, entry)).use { output ->
                            onBytes(input.copyTo(output, COPY_BUFFER_BYTES))
                        }
                    }
                } else {
                    // 目录
                    childTargetDir.mkdirs()
                    extractAssetDir(childAssetPath, childTargetDir, onBytes)
                }
            }
        }
    }

    /**
     * 从网络下载并解压模型（备用方案）
     */
    suspend fun downloadModel(
        model: VoskModel,
        onProgress: (Float) -> Unit = {}
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val modelDir = getModelPath(model.id)
        val tempZip = File(getModelsDir(), "${model.id}.zip")

        try {
            val url = java.net.URL(model.downloadUrl)
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 30_000
            conn.readTimeout = 120_000
            conn.connect()

            val totalSize = conn.contentLength.toLong()
            var downloadedSize = 0L

            java.io.BufferedInputStream(conn.inputStream).use { input ->
                FileOutputStream(tempZip).use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloadedSize += bytesRead
                        if (totalSize > 0) onProgress(downloadedSize.toFloat() / totalSize)
                    }
                }
            }
            conn.disconnect()

            if (modelDir.exists()) modelDir.deleteRecursively()
            modelDir.mkdirs()

            java.util.zip.ZipInputStream(tempZip.inputStream()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val file = File(modelDir, entry.name)
                    if (!file.canonicalPath.startsWith(modelDir.canonicalPath)) throw SecurityException("ZipSlip")
                    if (entry.isDirectory) file.mkdirs()
                    else { file.parentFile?.mkdirs(); FileOutputStream(file).use { zip.copyTo(it) } }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }

            tempZip.delete()
            Result.success(Unit)
        } catch (e: Exception) {
            tempZip.delete()
            modelDir.deleteRecursively()
            Result.failure(e)
        }
    }

    fun deleteModel(modelId: String) {
        getModelPath(modelId).deleteRecursively()
    }

    fun release() {}
}
