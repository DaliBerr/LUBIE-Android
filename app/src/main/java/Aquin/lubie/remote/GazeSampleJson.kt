package Aquin.lubie.remote

import org.json.JSONArray
import org.json.JSONObject

/**
 * Summary: Parses and serializes gaze sample JSON payloads used by UDP live data and session files.
 * @param none No constructor parameters.
 * @return Stateless JSON helper.
 */
object GazeSampleJson {

    /**
     * Summary: Parses one gaze sample JSON text.
     * @param jsonText Raw JSON payload text.
     * @return Parsed gaze sample or null when parsing fails.
     */
    fun parseOrNull(jsonText: String): GazeSample? {
        return try {
            fromJsonObject(JSONObject(jsonText))
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Summary: Serializes one gaze sample to a JSON object.
     * @param sample Gaze sample to encode.
     * @return JSON object representing the sample.
     */
    fun toJsonObject(sample: GazeSample): JSONObject {
        return JSONObject().apply {
            putNullableLong("eye_timestamp_ns", sample.eyeTimestampNs)
            putNullableLong("fpv_timestamp_ns", sample.fpvTimestampNs)
            putNullableUv("screen_uv", sample.screenUvX, sample.screenUvY)
            putNullableString("fpv_output_mode", sample.fpvOutputMode)
            putNullableBoolean("recording_active", sample.recordingActive)
            put("tracking_valid", sample.trackingValid)
            putNullableBoolean("feature_valid", sample.featureValid)
            putNullableString("feature_mode", sample.featureMode)
            putNullableString("calibration_state", sample.calibrationState)
            putNullableString("calibration_step", sample.calibrationStep)
            putNullableBoolean("calibrated", sample.calibrated)
            putNullableUv("calibration_target_uv", sample.calibrationTargetUvX, sample.calibrationTargetUvY)
            putNullableDouble("sync_skew_ms", sample.syncSkewMs)
            putNullableBoolean("sync_stale", sample.syncStale)
            putNullableBoolean("eye_stale", sample.eyeStale)
            putNullableBoolean("fpv_stale", sample.fpvStale)
            putNullableDouble("fps", sample.fps)
            putNullableDouble("inference_ms", sample.inferenceMs)
            putNullableString("status_message", sample.statusMessage)
        }
    }

    /**
     * Summary: Parses one gaze sample JSON object.
     * @param jsonObject Source JSON object.
     * @return Parsed gaze sample.
     */
    fun fromJsonObject(jsonObject: JSONObject): GazeSample {
        val screenUv = jsonObject.optNullableUv("screen_uv")
        val calibrationTarget = jsonObject.optNullableUv("calibration_target_uv")
        return GazeSample(
            eyeTimestampNs = jsonObject.optNullableLong("eye_timestamp_ns"),
            fpvTimestampNs = jsonObject.optNullableLong("fpv_timestamp_ns"),
            screenUvX = screenUv?.first,
            screenUvY = screenUv?.second,
            fpvOutputMode = jsonObject.optNullableString("fpv_output_mode"),
            recordingActive = jsonObject.optNullableBoolean("recording_active"),
            trackingValid = jsonObject.optBoolean("tracking_valid", false),
            featureValid = jsonObject.optNullableBoolean("feature_valid"),
            featureMode = jsonObject.optNullableString("feature_mode"),
            calibrationState = jsonObject.optNullableString("calibration_state"),
            calibrationStep = jsonObject.optNullableString("calibration_step"),
            calibrated = jsonObject.optNullableBoolean("calibrated"),
            calibrationTargetUvX = calibrationTarget?.first,
            calibrationTargetUvY = calibrationTarget?.second,
            syncSkewMs = jsonObject.optNullableDouble("sync_skew_ms"),
            syncStale = jsonObject.optNullableBoolean("sync_stale"),
            eyeStale = jsonObject.optNullableBoolean("eye_stale"),
            fpvStale = jsonObject.optNullableBoolean("fpv_stale"),
            fps = jsonObject.optNullableDouble("fps"),
            inferenceMs = jsonObject.optNullableDouble("inference_ms"),
            statusMessage = jsonObject.optNullableString("status_message"),
        )
    }

    private fun JSONObject.putNullableBoolean(
        key: String,
        value: Boolean?,
    ) {
        put(key, value ?: JSONObject.NULL)
    }

    private fun JSONObject.putNullableDouble(
        key: String,
        value: Double?,
    ) {
        put(key, value ?: JSONObject.NULL)
    }

    private fun JSONObject.putNullableLong(
        key: String,
        value: Long?,
    ) {
        put(key, value ?: JSONObject.NULL)
    }

    private fun JSONObject.putNullableString(
        key: String,
        value: String?,
    ) {
        put(key, value ?: JSONObject.NULL)
    }

    private fun JSONObject.putNullableUv(
        key: String,
        x: Double?,
        y: Double?,
    ) {
        if (x == null || y == null) {
            put(key, JSONObject.NULL)
            return
        }
        put(
            key,
            JSONArray().apply {
                put(x)
                put(y)
            },
        )
    }

    private fun JSONObject.optNullableBoolean(key: String): Boolean? {
        return if (!has(key) || isNull(key)) null else optBoolean(key)
    }

    private fun JSONObject.optNullableDouble(key: String): Double? {
        return if (!has(key) || isNull(key)) null else optDouble(key)
    }

    private fun JSONObject.optNullableLong(key: String): Long? {
        return if (!has(key) || isNull(key)) null else optLong(key)
    }

    private fun JSONObject.optNullableString(key: String): String? {
        return if (!has(key) || isNull(key)) null else optString(key)
    }

    private fun JSONObject.optNullableUv(key: String): Pair<Double, Double>? {
        if (!has(key) || isNull(key)) return null
        val array = optJSONArray(key) ?: return null
        if (array.length() < 2) return null
        return array.optDouble(0) to array.optDouble(1)
    }
}
