package com.aipirateradio.app.station

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.Instant

class ShowHistoryStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("show_history", Context.MODE_PRIVATE)

    fun recentPlayRecords(now: Instant = Instant.now()): List<PlayRecord> {
        val cutoff = now.minus(HISTORY_RETENTION)
        return loadEntries()
            .filter { it.plannedAt >= cutoff }
            .map { entry ->
                PlayRecord(
                    songId = entry.song.id,
                    title = entry.song.title,
                    artist = entry.song.artist,
                    album = entry.song.album,
                    genreTags = entry.song.genreTags,
                    startedAt = entry.plannedAt,
                    hadSegue = !entry.segueText.isNullOrBlank()
                )
            }
    }

    fun recordPreparedShow(show: PreparedShow, vibeId: String, now: Instant = Instant.now()) {
        val existing = loadEntries().filter { it.plannedAt >= now.minus(HISTORY_RETENTION) }
        val newEntries = show.tracks.mapIndexed { index, track ->
            ShowHistoryEntry(track.song, vibeId, now.plusSeconds(index.toLong()), track.segueText)
        }
        saveEntries((existing + newEntries).takeLast(MAX_HISTORY_ENTRIES))
    }

    fun clear() {
        preferences.edit().remove(KEY_HISTORY).commit()
    }

    private fun loadEntries(): List<ShowHistoryEntry> {
        val raw = preferences.getString(KEY_HISTORY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    array.optJSONObject(i)?.toShowHistoryEntry()?.let { add(it) }
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun saveEntries(entries: List<ShowHistoryEntry>) {
        val array = JSONArray().also { json -> entries.forEach { json.put(it.toJson()) } }
        preferences.edit().putString(KEY_HISTORY, array.toString()).commit()
    }

    private fun ShowHistoryEntry.toJson(): JSONObject {
        return JSONObject()
            .put("song", song.toJson())
            .put("vibeId", vibeId)
            .put("plannedAt", plannedAt.toString())
            .put("segueText", segueText)
    }

    private fun JSONObject.toShowHistoryEntry(): ShowHistoryEntry? {
        val song = optJSONObject("song")?.toSong() ?: return null
        val plannedAt = runCatching { Instant.parse(optString("plannedAt")) }.getOrNull() ?: return null
        return ShowHistoryEntry(song, optString("vibeId"), plannedAt, optString("segueText").takeIf { it.isNotBlank() })
    }

    private data class ShowHistoryEntry(
        val song: Song,
        val vibeId: String,
        val plannedAt: Instant,
        val segueText: String?
    )

    private companion object {
        const val KEY_HISTORY = "history_json"
        const val MAX_HISTORY_ENTRIES = 240
        val HISTORY_RETENTION: Duration = Duration.ofDays(30)
    }
}
