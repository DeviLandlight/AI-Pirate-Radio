package com.aipirateradio.bot

import com.aipirateradio.app.station.PreparedShow
import com.aipirateradio.app.station.Song
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

data class ListenerSongRequest(
    val artist: String?,
    val title: String?,
    val isArtistOnly: Boolean = false
) {
    fun displayName(): String =
        if (isArtistOnly) artist.orEmpty() else listOfNotNull(artist, title).joinToString(" - ")
}

data class RequestFitDecision(
    val decision: String,
    val fit: String,
    val reason: String
) {
    val isRejected: Boolean
        get() = decision.equals("reject", ignoreCase = true) || fit.equals("off", ignoreCase = true)

    val isStretch: Boolean
        get() = fit.equals("stretch", ignoreCase = true)
}

class RequestPlanner(
    private val apiKey: String,
    private val model: String
) {
    private val httpClient = HttpClient.newHttpClient()

    fun parse(input: String): ListenerSongRequest? {
        val fallback = input.parseSongRequestFallback()
        if (apiKey.isBlank()) return fallback
        val schema = JSONObject()
            .put("type", "object")
            .put("additionalProperties", false)
            .put(
                "properties",
                JSONObject()
                    .put("artist", JSONObject().put("type", JSONArray().put("string").put("null")))
                    .put("title", JSONObject().put("type", JSONArray().put("string").put("null")))
                    .put("requestType", JSONObject().put("type", "string").put("enum", JSONArray().put("song").put("artist")))
            )
            .put("required", JSONArray().put("artist").put("title").put("requestType"))
        val response = createTextResponse(
            instructions = """
                Extract a music request. Use requestType "song" when the listener named a specific song, and "artist" when they only named an artist or band and want the station to choose.
                Return JSON only. If the artist or title is unknown, use null.
            """.trimIndent(),
            input = input,
            schemaName = "listener_song_request",
            schema = schema
        ) ?: return fallback
        return runCatching {
            val json = JSONObject(response)
            val artist = json.optNullableString("artist")
            val title = json.optNullableString("title")
            val requestType = json.optString("requestType").trim()
            if (requestType.equals("artist", ignoreCase = true) && artist != null) {
                return@runCatching ListenerSongRequest(artist = artist, title = null, isArtistOnly = true)
            }
            if (title == null) return@runCatching fallback
            ListenerSongRequest(
                artist = artist,
                title = title,
                isArtistOnly = false
            )
        }.getOrNull() ?: fallback
    }

    fun chooseInsertionIndex(show: PreparedShow, nextTrackIndex: Int, requestedSong: Song): Int? {
        if (apiKey.isBlank() || show.tracks.isEmpty()) return null
        val lowerBound = nextTrackIndex.coerceIn(0, show.tracks.size)
        val tracks = show.tracks.mapIndexed { index, track ->
            JSONObject()
                .put("index", index)
                .put("artist", track.song.artist)
                .put("title", track.song.title)
        }
        val schema = JSONObject()
            .put("type", "object")
            .put("additionalProperties", false)
            .put(
                "properties",
                JSONObject()
                    .put("insertIndex", JSONObject().put("type", "integer"))
                    .put("reason", JSONObject().put("type", "string"))
            )
            .put("required", JSONArray().put("insertIndex").put("reason"))
        val input = JSONObject()
            .put("request", JSONObject().put("artist", requestedSong.artist).put("title", requestedSong.title))
            .put("minimumInsertIndex", lowerBound)
            .put("tracks", JSONArray(tracks))
            .toString()
        val response = createTextResponse(
            instructions = "Choose where to insert a listener request in a radio show. Do not put it before minimumInsertIndex. Prefer after the next song unless it flows better later. Return JSON only.",
            input = input,
            schemaName = "request_insert_index",
            schema = schema
        ) ?: return null
        return runCatching {
            JSONObject(response).optInt("insertIndex").coerceIn(lowerBound, show.tracks.size)
        }.getOrNull()
    }

    fun evaluateVibeFit(show: PreparedShow, nextTrackIndex: Int, vibeLabel: String, requestedSong: Song): RequestFitDecision? {
        if (apiKey.isBlank()) return null
        val upcoming = show.tracks
            .drop(nextTrackIndex.coerceIn(0, show.tracks.size))
            .take(6)
            .map { track -> JSONObject().put("artist", track.song.artist).put("title", track.song.title) }
        val schema = JSONObject()
            .put("type", "object")
            .put("additionalProperties", false)
            .put(
                "properties",
                JSONObject()
                    .put("decision", JSONObject().put("type", "string").put("enum", JSONArray().put("accept").put("reject")))
                    .put("fit", JSONObject().put("type", "string").put("enum", JSONArray().put("close").put("adjacent").put("stretch").put("off")))
                    .put("reason", JSONObject().put("type", "string"))
            )
            .put("required", JSONArray().put("decision").put("fit").put("reason"))
        val input = JSONObject()
            .put("currentVibe", vibeLabel)
            .put("request", JSONObject().put("artist", requestedSong.artist).put("title", requestedSong.title))
            .put("upcomingSongs", JSONArray(upcoming))
            .toString()
        val response = createTextResponse(
            instructions = """
                Decide whether a listener song request belongs in the current radio block.
                Be generous: accept close, adjacent, and interesting stretch fits.
                Reject only if the request clearly clashes with the current vibe and upcoming songs.
                Return a short reason the bot can show to the listener. Do not be rude.
                Return JSON only.
            """.trimIndent(),
            input = input,
            schemaName = "request_vibe_fit",
            schema = schema
        ) ?: return null
        return runCatching {
            val json = JSONObject(response)
            RequestFitDecision(
                decision = json.optString("decision"),
                fit = json.optString("fit"),
                reason = json.optString("reason").trim()
            )
        }.getOrNull()
    }

    private fun createTextResponse(instructions: String, input: String, schemaName: String, schema: JSONObject): String? {
        val body = JSONObject()
            .put("model", model)
            .put("instructions", instructions)
            .put("input", input)
            .put("text", JSONObject().put("format", JSONObject().put("type", "json_schema").put("name", schemaName).put("strict", true).put("schema", schema)))
            .toString()
        val request = HttpRequest.newBuilder(URI("https://api.openai.com/v1/responses"))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        return runCatching {
            httpClient.send(request, HttpResponse.BodyHandlers.ofString()).let { response ->
                if (response.statusCode() in 200..299) extractResponseText(JSONObject(response.body())) else null
            }
        }.getOrNull()
    }

    private fun extractResponseText(json: JSONObject): String? {
        json.optString("output_text").takeIf { it.isNotBlank() }?.let { return it }
        val output = json.optJSONArray("output") ?: return null
        for (i in 0 until output.length()) {
            val content = output.optJSONObject(i)?.optJSONArray("content") ?: continue
            for (j in 0 until content.length()) {
                content.optJSONObject(j)?.optString("text")?.takeIf { it.isNotBlank() }?.let { return it }
            }
        }
        return null
    }
}

private fun JSONObject.optNullableString(name: String): String? =
    if (isNull(name)) null else optString(name).trim().takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }

fun String.parseSongRequestFallback(): ListenerSongRequest? {
    val cleaned = trim().replace(Regex("\\s+"), " ")
    if (cleaned.isBlank()) return null
    val dashParts = cleaned.split(" - ", limit = 2)
    if (dashParts.size == 2 && dashParts[1].isNotBlank()) {
        return ListenerSongRequest(dashParts[0].takeIf { it.isNotBlank() }, dashParts[1], isArtistOnly = false)
    }
    val byMatch = Regex("(.+)\\s+by\\s+(.+)", RegexOption.IGNORE_CASE).matchEntire(cleaned)
    if (byMatch != null) {
        val title = byMatch.groupValues[1].trim()
        val artist = byMatch.groupValues[2].trim()
        if (title.isNotBlank()) return ListenerSongRequest(artist.takeIf { it.isNotBlank() }, title, isArtistOnly = false)
    }
    return ListenerSongRequest(cleaned, null, isArtistOnly = true)
}
