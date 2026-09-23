package dev.local.audiodoctor

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import org.json.JSONObject
import java.time.Instant
import kotlin.math.PI
import kotlin.math.sin

class AudioDiagnostics(context: Context) {
    val manager = context.getSystemService(AudioManager::class.java)

    fun snapshot(): AudioSnapshot {
        val all = manager.getDevices(AudioManager.GET_DEVICES_ALL)
        val outputs = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        return AudioSnapshot(
            Instant.now().toString(), modeName(manager.mode),
            manager.getStreamVolume(AudioManager.STREAM_MUSIC), manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
            manager.isStreamMute(AudioManager.STREAM_MUSIC), manager.isMusicActive,
            all.filter { it.isSink && isConnectedKind(it.type) }.map(::deviceName),
            outputs.map(::deviceName)
        )
    }

    fun runTest(): JSONObject {
        val sampleRate = 44_100
        val durationSeconds = 0.8
        val samples = ShortArray((sampleRate * durationSeconds).toInt()) { i ->
            val frequency = if (i < samplesHalf(sampleRate, durationSeconds)) 660.0 else 880.0
            (sin(2.0 * PI * frequency * i / sampleRate) * Short.MAX_VALUE * 0.14).toInt().toShort()
        }
        var track: AudioTrack? = null
        val result = JSONObject()
        try {
            val buffer = maxOf(samples.size * 2, AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT))
            track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(sampleRate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(buffer).build()
            result.put("trackState", if (track.state == AudioTrack.STATE_INITIALIZED) "INITIALIZED" else "UNINITIALIZED")
            if (track.state != AudioTrack.STATE_INITIALIZED) error("AudioTrack did not initialize")
            track.play()
            val written = track.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
            result.put("samplesRequested", samples.size).put("samplesWritten", written).put("writeSucceeded", written == samples.size)
            if (written < 0) error("AudioTrack write failed: $written")
            Thread.sleep(950)
            result.put("playbackHeadFrames", track.playbackHeadPosition.toLong())
                .put("playbackPositionAdvanced", track.playbackHeadPosition > 0)
                .put("underrunCount", track.underrunCount).put("completedAt", Instant.now().toString())
            track.stop()
        } catch (t: Throwable) {
            result.put("error", "${t.javaClass.simpleName}: ${t.message}")
        } finally {
            runCatching { track?.release() }
            result.put("trackReleased", true)
        }
        return result
    }

    private fun samplesHalf(rate: Int, duration: Double) = (rate * duration / 2).toInt()
    private fun deviceName(d: AudioDeviceInfo) = "${typeName(d.type)}${d.productName?.takeIf { it.isNotBlank() }?.let { " — $it" } ?: ""} (id ${d.id})"
    private fun isConnectedKind(type: Int) = type != AudioDeviceInfo.TYPE_BUILTIN_EARPIECE && type != AudioDeviceInfo.TYPE_BUILTIN_SPEAKER && type != AudioDeviceInfo.TYPE_BUILTIN_MIC
    private fun typeName(type: Int) = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Built-in speaker"; AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Built-in earpiece"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth A2DP"; AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth SCO"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired headphones"; AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headset"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB audio"; AudioDeviceInfo.TYPE_USB_HEADSET -> "USB headset"
        AudioDeviceInfo.TYPE_HDMI -> "HDMI"; else -> "Audio device type $type"
    }
    companion object {
        fun modeName(mode: Int) = when (mode) {
            AudioManager.MODE_NORMAL -> "NORMAL"; AudioManager.MODE_RINGTONE -> "RINGTONE"
            AudioManager.MODE_IN_CALL -> "IN_CALL"; AudioManager.MODE_IN_COMMUNICATION -> "IN_COMMUNICATION"
            AudioManager.MODE_CALL_SCREENING -> "CALL_SCREENING"; else -> "Unknown ($mode)"
        }
    }
}
