package Aquin.lubie.tracking.segmentation

import android.content.Context
import java.io.File
import java.io.IOException

private const val MODEL_ASSET_DIR = "models"

/**
 * Summary: Stores the copied model files needed by ONNX Runtime.
 * @param modelFile Local copied ONNX file.
 * @param externalDataFile Optional copied external data file.
 * @return Immutable local model file bundle.
 */
data class LocalModelFiles(
    val modelFile: File,
    val externalDataFile: File? = null,
)

/**
 * Summary: Copies model assets into app-private storage for ONNX Runtime sessions.
 * @param context Application context used for asset and file access.
 * @return Repository for local ONNX model file preparation.
 */
class ModelAssetRepository(context: Context) {

    private val appContext = context.applicationContext
    private val assetManager = appContext.assets
    private val modelsDir = File(appContext.filesDir, "models")

    /**
     * Summary: Returns whether the requested model asset is bundled with the app.
     * @param modelType Requested model type.
     * @return True when the ONNX asset exists in assets/models.
     */
    fun isModelAvailable(modelType: SegmentationModelType): Boolean {
        return assetExists(modelAssetPath(modelType))
    }

    /**
     * Summary: Returns the bundled segmentation model types in display order.
     * @param none No parameters.
     * @return Available model types backed by assets/models.
     */
    fun availableModelTypes(): List<SegmentationModelType> {
        return SegmentationModelType.entries.filter { modelType ->
            isModelAvailable(modelType)
        }
    }

    /**
     * Summary: Copies the requested model bundle into app-private storage if needed.
     * @param modelType Requested model type.
     * @return Local copied model files or null when the asset is unavailable.
     */
    fun ensureLocalModelFiles(modelType: SegmentationModelType): LocalModelFiles? {
        if (!isModelAvailable(modelType)) {
            return null
        }
        if (!modelsDir.exists()) {
            modelsDir.mkdirs()
        }

        val localModelFile = File(modelsDir, modelType.assetFileName)
        copyAssetIfNeeded(modelAssetPath(modelType), localModelFile)

        val externalDataName = externalDataFileName(modelType)
        val localExternalDataFile = if (externalDataName != null && assetExists("$MODEL_ASSET_DIR/$externalDataName")) {
            val file = File(modelsDir, externalDataName)
            copyAssetIfNeeded("$MODEL_ASSET_DIR/$externalDataName", file)
            file
        } else {
            null
        }

        return LocalModelFiles(
            modelFile = localModelFile,
            externalDataFile = localExternalDataFile,
        )
    }

    private fun copyAssetIfNeeded(
        assetPath: String,
        destinationFile: File,
    ) {
        if (destinationFile.exists() && destinationFile.length() > 0L) {
            return
        }
        assetManager.open(assetPath).use { input ->
            destinationFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
    }

    private fun assetExists(assetPath: String): Boolean {
        return try {
            assetManager.open(assetPath).close()
            true
        } catch (_: IOException) {
            false
        }
    }

    private fun modelAssetPath(modelType: SegmentationModelType): String {
        return "$MODEL_ASSET_DIR/${modelType.assetFileName}"
    }

    private fun externalDataFileName(modelType: SegmentationModelType): String? {
        return if (modelType == SegmentationModelType.FP32) {
            "${modelType.assetFileName}.data"
        } else {
            null
        }
    }
}
