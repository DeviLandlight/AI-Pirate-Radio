package com.aipirateradio.bot

import com.aipirateradio.app.station.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration

data class MusicBrainzAlbum(
    val id: String,
    val title: String,
    val artist: String,
    val releaseYear: Int?,
    val tracks: List<Song>
)

class MusicBrainzAlbumClient(
    private val userAgent: String = "AI Pirate Radio Discord Bot/1.0 (local personal project)"
) {
    private val httpClient = HttpClient.newHttpClient()

    suspend fun findAlbum(artist: String, album: String?): MusicBrainzAlbum? = withContext(Dispatchers.IO) {
        val releaseId = searchReleases(artist, album).firstOrNull() ?: return@withContext null
        delay(1_100)
        fetchRelease(releaseId, requestedArtist = artist)
    }

    private fun searchReleases(artist: String, album: String?): List<String> {
        val queryParts = mutableListOf(
            "artist:${artist.quoteForMusicBrainz()}",
            "primarytype:album",
            "status:official"
        )
        album?.trim()?.takeIf { it.isNotBlank() }?.let {
            queryParts += "release:${it.quoteForMusicBrainz()}"
        }
        val query = queryParts.joinToString(" AND ")
        val uri = URI(
            "https://musicbrainz.org/ws/2/release?fmt=json&limit=8&query=${
                URLEncoder.encode(query, StandardCharsets.UTF_8)
            }"
        )
        val json = getJson(uri) ?: return emptyList()
        val releases = json.optJSONArray("releases") ?: return emptyList()
        return (0 until releases.length())
            .mapNotNull { releases.optJSONObject(it) }
            .sortedWith(
                compareByDescending<JSONObject> { it.optInt("score", 0) }
                    .thenBy { it.optString("date").takeIf { date -> date.isNotBlank() } ?: "9999" }
            )
            .mapNotNull { it.optString("id").takeIf { id -> id.isNotBlank() } }
    }

    private fun fetchRelease(releaseId: String, requestedArtist: String): MusicBrainzAlbum? {
        val uri = URI("https://musicbrainz.org/ws/2/release/$releaseId?fmt=json&inc=recordings+artist-credits")
        val json = getJson(uri) ?: return null
        val title = json.optString("title").trim().takeIf { it.isNotBlank() } ?: return null
        val artist = json.artistCreditName().ifBlank { requestedArtist }
        val year = json.optString("date").takeIf { it.length >= 4 }?.take(4)?.toIntOrNull()
        val media = json.optJSONArray("media") ?: return null
        val tracks = mutableListOf<Song>()
        for (mediaIndex in 0 until media.length()) {
            val medium = media.optJSONObject(mediaIndex) ?: continue
            val trackArray = medium.optJSONArray("tracks") ?: continue
            for (trackIndex in 0 until trackArray.length()) {
                val track = trackArray.optJSONObject(trackIndex) ?: continue
                val trackTitle = track.optString("title")
                    .takeIf { it.isNotBlank() }
                    ?: track.optJSONObject("recording")?.optString("title")?.takeIf { it.isNotBlank() }
                    ?: continue
                val position = track.optInt("position", trackIndex + 1)
                val lengthMs = track.optLong("length", 0L)
                    .takeIf { it > 0L }
                    ?: track.optJSONObject("recording")?.optLong("length", 0L)?.takeIf { it > 0L }
                tracks += Song(
                    id = "musicbrainz:$releaseId:${mediaIndex + 1}:$position:${trackTitle}".stableId(),
                    audioUri = "musicbrainz:$releaseId:${mediaIndex + 1}:$position",
                    title = trackTitle,
                    artist = artist,
                    album = title,
                    releaseYear = year,
                    genreTags = listOf("album"),
                    moodTags = listOf("album_mode"),
                    duration = lengthMs?.let { Duration.ofMillis(it) } ?: Duration.ofMinutes(3)
                )
            }
        }
        return MusicBrainzAlbum(
            id = releaseId,
            title = title,
            artist = artist,
            releaseYear = year,
            tracks = tracks
        ).takeIf { it.tracks.isNotEmpty() }
    }

    private fun getJson(uri: URI): JSONObject? {
        val request = HttpRequest.newBuilder(uri)
            .header("Accept", "application/json")
            .header("User-Agent", userAgent)
            .GET()
            .build()
        return runCatching {
            httpClient.send(request, HttpResponse.BodyHandlers.ofString()).let { response ->
                if (response.statusCode() in 200..299) JSONObject(response.body()) else null
            }
        }.getOrNull()
    }
}

private fun JSONObject.artistCreditName(): String {
    val credits = optJSONArray("artist-credit") ?: return ""
    return (0 until credits.length())
        .mapNotNull { credits.optJSONObject(it)?.optString("name")?.takeIf { name -> name.isNotBlank() } }
        .joinToString("")
        .trim()
}

private fun String.quoteForMusicBrainz(): String =
    "\"${replace("\\", "\\\\").replace("\"", "\\\"")}\""

private fun String.stableId(): String {
    val digest = MessageDigest.getInstance("SHA-1").digest(toByteArray())
    return digest.joinToString("") { "%02x".format(it) }
}
