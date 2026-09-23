package dev.local.audiodoctor

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class DiagnosticRepository(context: Context) {
    private val prefs = context.getSharedPreferences("diagnostic_log", Context.MODE_PRIVATE)

    fun load(): List<LogEntry> = runCatching {
        val a = JSONArray(prefs.getString("entries", "[]"))
        (0 until a.length()).map { LogEntry.fromJson(a.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    fun save(entries: List<LogEntry>) {
        prefs.edit().putString("entries", JSONArray().apply { entries.forEach { put(it.json()) } }.toString()).apply()
    }

    fun export(entries: List<LogEntry>): String = JSONObject().apply {
        put("schemaVersion", 1); put("app", "Audio Doctor Stage 1")
        put("device", deviceJson()); put("events", JSONArray().apply { entries.forEach { put(it.json()) } })
        put("limitations", "Android stream progress does not prove that a physical speaker produced audible sound.")
    }.toString(2)
}
