package com.lv999call.app.audio

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
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

        /** 预定义模型列表 */
        val AVAILABLE_MODELS = listOf(
            VoskModel("vosk-model-small-cn-0.22", "中文（小）", "zh", "~50MB", "https://alphacephei.com/vosk/models/vosk-model-small-cn-0.22.zip"),
        )
    }

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

    fun isModelAvailable(modelId: String): Boolean {
        // 检查是否已解压到内部存储
        val modelDir = getModelPath(modelId)
        if (modelDir.exists() && modelDir.isDirectory && modelDir.listFiles()?.isNotEmpty() == true) {
            return true
        }
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
     * @return 模型路径，失败返回 null
     */
    suspend fun ensureModelReady(modelId: String): String? = withContext(Dispatchers.IO) {
        val modelDir = getModelPath(modelId)

        // 已在内部存储
        if (modelDir.exists() && modelDir.listFiles()?.isNotEmpty() == true) {
            return@withContext modelDir.absolutePath
        }

        // 尝试从 assets 解压
        val extracted = extractFromAssets(modelId)
        if (extracted) {
            return@withContext modelDir.absolutePath
        }

        Log.e(TAG, "模型不可用: $modelId")
        null
    }

    /**
     * 从 assets 解压模型到内部存储（仅首次）
     */
    private fun extractFromAssets(modelId: String): Boolean {
        val assetPath = "$ASSETS_MODELS_DIR/$modelId"
        val targetDir = getModelPath(modelId)

        return try {
            val files = context.assets.list(assetPath)
            if (files.isNullOrEmpty()) {
                Log.d(TAG, "assets中无模型: $assetPath")
                return false
            }

            targetDir.mkdirs()
            Log.d(TAG, "从assets解压模型: $modelId")

            extractAssetDir(assetPath, targetDir)

            Log.d(TAG, "模型解压完成: ${targetDir.absolutePath}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "assets解压失败: ${e.message}")
            targetDir.deleteRecursively()
            false
        }
    }

    /** 递归解压 assets 目录 */
    private fun extractAssetDir(assetPath: String, targetDir: File) {
        val entries = context.assets.list(assetPath) ?: return

        if (entries.isEmpty()) {
            // 是文件，复制
            context.assets.open(assetPath).use { input ->
                FileOutputStream(File(targetDir, assetPath.substringAfterLast('/'))).use { output ->
                    input.copyTo(output)
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
                            input.copyTo(output)
                        }
                    }
                } else {
                    // 目录
                    childTargetDir.mkdirs()
                    extractAssetDir(childAssetPath, childTargetDir)
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
