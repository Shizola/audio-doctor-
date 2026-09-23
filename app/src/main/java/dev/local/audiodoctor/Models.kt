package dev.local.audiodoctor

import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

data class AudioSnapshot(
    val timestamp: String,
    val mode: String,
    val volume: Int,
    val maxVolume: Int,
    val muted: Boolean,
    val musicActive: Boolean,
    val connectedDevices: List<String>,
    val outputDevices: List<String>
) {
    fun json() = JSONObject().apply {
        put("timestamp", timestamp); put("audioMode", mode)
        put("mediaVolume", volume); put("mediaMaxVolume", maxVolume)
        put("mediaMuted", muted); put("musicActiveReportedByAndroid", musicActive)
        put("connectedDevices", JSONArray(connectedDevices)); put("availableOutputDevices", JSONArray(outputDevices))
    }
}

data class LogEntry(
    val timestamp: String = Instant.now().toString(),
    val type: String,
    val message: String,
    val snapshot: AudioSnapshot? = null,
    val details: JSONObject? = null
) {
    fun json() = JSONObject().apply {
        put("timestamp", timestamp); put("type", type); put("message", message)
        snapshot?.let { put("audioSnapshot", it.json()) }
        details?.let { put("details", it) }
    }

    companion object {
        fun fromJson(o: JSONObject) = LogEntry(
            o.optString("timestamp"), o.optString("type"), o.optString("message"),
            o.optJSONObject("audioSnapshot")?.let { s -> AudioSnapshot(
                s.optString("timestamp"), s.optString("audioMode"), s.optInt("mediaVolume"),
                s.optInt("mediaMaxVolume"), s.optBoolean("mediaMuted"),
                s.optBoolean("musicActiveReportedByAndroid"),
                s.optJSONArray("connectedDevices").strings(), s.optJSONArray("availableOutputDevices").strings()
            ) }, o.optJSONObject("details")
        )
    }
}

private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).map { optString(it) }

fun deviceJson() = JSONObject().apply {
    put("manufacturer", Build.MANUFACTURER); put("model", Build.MODEL)
    put("androidVersion", Build.VERSION.RELEASE); put("apiLevel", Build.VERSION.SDK_INT)
}
