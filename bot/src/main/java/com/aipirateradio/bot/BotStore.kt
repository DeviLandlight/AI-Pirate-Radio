package com.aipirateradio.bot

import com.aipirateradio.app.station.PlayRecord
import com.aipirateradio.app.station.PreparedShow
import com.aipirateradio.app.station.PreparedShowTrack
import com.aipirateradio.app.station.Song
import org.json.JSONArray
import org.json.JSONObject
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

class BotStore(
    private val root: Path
) {
    fun loadSession(key: RadioSessionKey): PersistedRadioSession {
        val file = sessionFile(key)
        if (!file.exists()) return PersistedRadioSession()
        return runCatching {
            val json = JSONObject(file.readText())
            PersistedRadioSession(
                preparedShow = json.optJSONObject("preparedShow")?.toPreparedShow(),
                history = json.optJSONArray("history").toPlayRecords(),
                nextTrackIndex = json.optInt("nextTrackIndex", 0).coerceAtLeast(0),
                vibeLabel = json.optString("vibeLabel").takeIf { it.isNotBlank() } ?: "Emotional Authentic Rock",
                queueVibes = json.optJSONArray("queueVibes").toStringList(),
                requestCooldowns = json.optJSONObject("requestCooldowns").toInstantMap(),
                callIns = json.optJSONArray("callIns").toCallIns(),
                askCooldowns = json.optJSONObject("askCooldowns").toInstantMap()
            )
        }.getOrDefault(PersistedRadioSession())
    }

    fun saveSession(key: RadioSessionKey, session: GuildRadioSession) {
        root.createDirectories()
        val json = JSONObject()
            .put("preparedShow", session.preparedShow?.toJson())
            .put("history", JSONArray().also { array -> session.history.forEach { array.put(it.toJson()) } })
            .put("nextTrackIndex", session.nextTrackIndex)
            .put("vibeLabel", session.vibeLabel)
            .put("queueVibes", JSONArray(session.queueVibes))
            .put("requestCooldowns", session.requestCooldowns.toJson())
            .put("callIns", JSONArray().also { array -> session.callIns.forEach { array.put(it.toJson()) } })
            .put("askCooldowns", session.askCooldowns.toJson())
        sessionFile(key).writeText(json.toString(2))
    }

    private fun sessionFile(key: RadioSessionKey): Path {
        root.createDirectories()
        return root.resolve("${key.guildId}-${key.voiceChannelId}.json")
    }
}

data class PersistedRadioSession(
    val preparedShow: PreparedShow? = null,
    val history: List<PlayRecord> = emptyList(),
    val nextTrackIndex: Int = 0,
    val vibeLabel: String = "Emotional Authentic Rock",
    val queueVibes: List<String> = emptyList(),
    val requestCooldowns: Map<Long, Instant> = emptyMap(),
    val callIns: List<CallInQuestion> = emptyList(),
    val askCooldowns: Map<Long, Instant> = emptyMap()
)

fun defaultBotDataPath(): Path = Path.of("bot-data")

private fun PreparedShow.toJson(): JSONObject {
    return JSONObject().put("tracks", JSONArray().also { array ->
        tracks.forEach { array.put(it.toJson()) }
    })
}

private fun PreparedShowTrack.toJson(): JSONObject {
    return JSONObject()
        .put("song", song.toJson())
        .put("segueText", segueText)
        .put("pickReason", pickReason)
}

private fun JSONObject.toPreparedShow(): PreparedShow {
    return PreparedShow(
        tracks = optJSONArray("tracks").toPreparedShowTracks()
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

private fun Song.toJson(): JSONObject {
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

private fun JSONObject.toSong(): Song {
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

private fun PlayRecord.toJson(): JSONObject {
    return JSONObject()
        .put("songId", songId)
        .put("title", title)
        .put("artist", artist)
        .put("album", album)
        .put("genreTags", JSONArray(genreTags))
        .put("startedAt", startedAt.toString())
        .put("endedAt", endedAt?.toString())
        .put("hadSegue", hadSegue)
}

private fun JSONObject.toPlayRecord(): PlayRecord? {
    val startedAt = runCatching { Instant.parse(optString("startedAt")) }.getOrNull() ?: return null
    val endedAt = optString("endedAt").takeIf { it.isNotBlank() && it != "null" }?.let {
        runCatching { Instant.parse(it) }.getOrNull()
    }
    return PlayRecord(
        songId = optString("songId"),
        title = optString("title"),
        artist = optString("artist"),
        album = optString("album").takeIf { it.isNotBlank() && it != "null" },
        genreTags = optJSONArray("genreTags").toStringList(),
        startedAt = startedAt,
        endedAt = endedAt,
        hadSegue = optBoolean("hadSegue", false)
    )
}

private fun JSONArray?.toPreparedShowTracks(): List<PreparedShowTrack> {
    if (this == null) return emptyList()
    return buildList {
        for (i in 0 until length()) {
            optJSONObject(i)?.toPreparedShowTrack()?.let { add(it) }
        }
    }
}

private fun JSONArray?.toPlayRecords(): List<PlayRecord> {
    if (this == null) return emptyList()
    return buildList {
        for (i in 0 until length()) {
            optJSONObject(i)?.toPlayRecord()?.let { add(it) }
        }
    }
}

private fun JSONArray?.toStringList(): List<String> {
    if (this == null) return emptyList()
    return buildList {
        for (i in 0 until length()) {
            optString(i).takeIf { it.isNotBlank() }?.let { add(it) }
        }
    }
}

private fun Map<Long, Instant>.toJson(): JSONObject {
    return JSONObject().also { json ->
        forEach { (userId, timestamp) -> json.put(userId.toString(), timestamp.toString()) }
    }
}

private fun JSONObject?.toInstantMap(): Map<Long, Instant> {
    if (this == null) return emptyMap()
    return buildMap {
        keys().forEach { key ->
            val userId = key.toLongOrNull() ?: return@forEach
            val timestamp = runCatching { Instant.parse(optString(key)) }.getOrNull() ?: return@forEach
            put(userId, timestamp)
        }
    }
}

private fun JSONArray?.toCallIns(): List<CallInQuestion> {
    if (this == null) return emptyList()
    return buildList {
        for (i in 0 until length()) {
            optJSONObject(i)?.toCallIn()?.let { add(it) }
        }
    }
}

private fun JSONObject.toCallIn(): CallInQuestion? {
    val askedAt = runCatching { Instant.parse(optString("askedAt")) }.getOrNull() ?: return null
    val question = optString("question").takeIf { it.isNotBlank() } ?: return null
    return CallInQuestion(
        userId = optLong("userId"),
        displayName = optString("displayName").takeIf { it.isNotBlank() } ?: "caller",
        question = question,
        askedAt = askedAt
    )
}

private fun CallInQuestion.toJson(): JSONObject {
    return JSONObject()
        .put("userId", userId)
        .put("displayName", displayName)
        .put("question", question)
        .put("askedAt", askedAt.toString())
}
