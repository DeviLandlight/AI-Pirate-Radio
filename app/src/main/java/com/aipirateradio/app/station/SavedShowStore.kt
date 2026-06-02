package com.aipirateradio.app.station

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration

class SavedShowStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("saved_show", Context.MODE_PRIVATE)

    fun save(show: PreparedShow) {
        preferences.edit().putString(KEY_SHOW, show.toJson().toString()).commit()
    }

    fun load(): PreparedShow? {
        val raw = preferences.getString(KEY_SHOW, null) ?: return null
        return runCatching { JSONObject(raw).toPreparedShow() }.getOrNull()
    }

    fun saveNextTrackIndex(index: Int) {
        preferences.edit().putInt(KEY_NEXT_TRACK_INDEX, index.coerceAtLeast(0)).commit()
    }

    fun loadNextTrackIndex(): Int {
        return preferences.getInt(KEY_NEXT_TRACK_INDEX, 0).coerceAtLeast(0)
    }

    fun clear() {
        preferences.edit().remove(KEY_SHOW).remove(KEY_NEXT_TRACK_INDEX).commit()
    }

    private fun PreparedShow.toJson(): JSONObject {
        return JSONObject().put("tracks", JSONArray().also { array ->
            tracks.forEach { track -> array.put(track.toJson()) }
        })
    }

    private fun PreparedShowTrack.toJson(): JSONObject {
        return JSONObject()
            .put("song", song.toJson())
            .put("segueText", segueText)
            .put("pickReason", pickReason)
    }

    private fun JSONObject.toPreparedShow(): PreparedShow {
        val tracksArray = optJSONArray("tracks") ?: JSONArray()
        return PreparedShow(
            tracks = buildList {
                for (i in 0 until tracksArray.length()) {
                    tracksArray.optJSONObject(i)?.toPreparedShowTrack()?.let { add(it) }
                }
            }
        )
    }

    private fun JSONObject.toPreparedShowTrack(): PreparedShowTrack? {
        val song = optJSONObject("song")?.toSong() ?: return null
        return PreparedShowTrack(
            song = song,
            segueText = optString("segueText").takeIf { it.isNotBlank() },
            pickReason = optString("pickReason").takeIf { it.isNotBlank() }
        )
    }

    private companion object {
        const val KEY_SHOW = "show_json"
        const val KEY_NEXT_TRACK_INDEX = "next_track_index"
    }
}

fun Song.toJson(): JSONObject {
    return JSONObject()
        .put("id", id)
        .put("audioUri", audioUri)
        .put("title", title)
        .put("artist", artist)
        .put("album", album)
        .put("releaseYear", releaseYear)
        .put("genreTags", JSONArray(genreTags))
        .put("moodTags", JSONArray(moodTags))
        .put("durationMs", duration.toMillis())
        .put("enabled", enabled)
}

fun JSONObject.toSong(): Song {
    return Song(
        id = optString("id"),
        audioUri = optString("audioUri"),
        title = optString("title"),
        artist = optString("artist"),
        album = optString("album").takeIf { it.isNotBlank() && it != "null" },
        releaseYear = if (has("releaseYear") && !isNull("releaseYear")) optInt("releaseYear") else null,
        genreTags = optJSONArray("genreTags").toStringList(),
        moodTags = optJSONArray("moodTags").toStringList(),
        duration = Duration.ofMillis(optLong("durationMs", 180_000L)),
        enabled = optBoolean("enabled", true)
    )
}

fun JSONArray?.toStringList(): List<String> {
    if (this == null) return emptyList()
    return buildList {
        for (i in 0 until length()) {
            optString(i).takeIf { it.isNotBlank() }?.let { add(it) }
        }
    }
}
