package com.aipirateradio.bot

import com.aipirateradio.app.station.Candidate
import com.aipirateradio.app.station.ArtistGroups
import com.aipirateradio.app.station.PlayRecord
import com.aipirateradio.app.station.PreparedShow
import com.aipirateradio.app.station.PreparedShowTrack
import com.aipirateradio.app.station.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

class BatchShowPlanner(
    private val apiKey: String,
    private val model: String
) {
    private val httpClient = HttpClient.newHttpClient()

    suspend fun createVibeBrief(vibeLabel: String, seedArtists: List<String>): String? = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext null
        val schema = JSONObject()
            .put("type", "object")
            .put("additionalProperties", false)
            .put(
                "properties",
                JSONObject()
                    .put("brief", JSONObject().put("type", "string"))
                    .put("displayName", JSONObject().put("type", "string"))
            )
            .put("required", JSONArray().put("brief").put("displayName"))
        val input = JSONObject()
            .put("vibeLabel", vibeLabel)
            .put("seedArtists", JSONArray(seedArtists))
            .toString()
        val response = createTextResponse(
            instructions = """
                Create a concise target vibe brief for a radio show.
                If seed artists are supplied, infer the common musical/emotional thread without overfitting to one artist's gimmick.
                Include texture, energy, vocal feel, lyrical/theatrical tendencies, and boundaries for what would feel off-vibe.
                Keep it under 70 words. Return JSON only.
            """.trimIndent(),
            input = input,
            schemaName = "radio_vibe_brief",
            schema = schema
        ) ?: return@withContext null
        runCatching { JSONObject(response).optString("brief").trim().takeIf { it.isNotBlank() } }.getOrNull()
    }

    suspend fun planPlaylist(
        vibeLabel: String,
        vibeBrief: String,
        candidates: List<Candidate>,
        history: List<PlayRecord>,
        targetSongCount: Int
    ): List<BatchPickedSong>? = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || candidates.isEmpty()) return@withContext null
        val schema = JSONObject()
            .put("type", "object")
            .put("additionalProperties", false)
            .put(
                "properties",
                JSONObject()
                    .put(
                        "picks",
                        JSONObject()
                            .put("type", "array")
                            .put("minItems", targetSongCount)
                            .put("maxItems", targetSongCount)
                            .put(
                                "items",
                                JSONObject()
                                    .put("type", "object")
                                    .put("additionalProperties", false)
                                    .put(
                                        "properties",
                                        JSONObject()
                                            .put("candidateId", JSONObject().put("type", "string"))
                                            .put("reason", JSONObject().put("type", "string"))
                                    )
                                    .put("required", JSONArray().put("candidateId").put("reason"))
                            )
                    )
            )
            .put("required", JSONArray().put("picks"))
        val input = JSONObject()
            .put("targetSongCount", targetSongCount)
            .put("vibeLabel", vibeLabel)
            .put("vibeBrief", vibeBrief)
            .put("recentHistory", JSONArray(history.takeLast(12).map { it.toJson() }))
            .put("candidates", JSONArray(candidates.take(96).map { it.toJson() }))
            .toString()
        val response = createTextResponse(
            instructions = """
                Build one cohesive radio playlist from the candidate list.
                Pick exactly targetSongCount candidate IDs and order them for flow.
                Fit the target vibe first, while allowing adjacent contrast so the set does not become samey.
                Prefer higher stationScore unless another candidate clearly creates better vibe fit or flow.
                Prefer at most 2 songs per artist in a 12-song playlist when alternatives exist.
                Prefer at most 3 songs from the same artistFamily in a 12-song playlist when alternatives exist.
                Give each requested seed artist at least one slot when candidates exist for that seed.
                If an artist repeats, place those songs as far apart as possible.
                If an artistFamily repeats, place those songs as far apart as possible.
                Do not group the same artist back-to-back or in nearby clusters.
                Avoid repeating the same artist, related artist family, or same-feeling track too close together.
                Include a few familiar tracks and a few deeper cuts when available.
                Do not choose outside the candidate list. Do not use the same candidate twice.
                Return JSON only.
            """.trimIndent(),
            input = input,
            schemaName = "batch_playlist",
            schema = schema
        ) ?: return@withContext null
        runCatching<List<BatchPickedSong>?> {
            val picks = JSONObject(response).optJSONArray("picks") ?: return@runCatching null
            val candidateById = candidates.associateBy { it.song.id }
            val used = mutableSetOf<String>()
            val pickedSongs = mutableListOf<BatchPickedSong>()
            for (i in 0 until picks.length()) {
                val pick = picks.optJSONObject(i) ?: continue
                val id = pick.optString("candidateId")
                val candidate = candidateById[id] ?: continue
                if (!used.add(id)) continue
                pickedSongs += BatchPickedSong(candidate.song, pick.optString("reason").trim())
            }
            pickedSongs.takeIf { it.size == targetSongCount }
        }.getOrNull()
    }

    suspend fun writeSegueBatch(
        vibeBrief: String,
        tracks: List<PreparedShowTrack>,
        spacing: Int = 2
    ): Map<Int, String>? = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || tracks.isEmpty()) return@withContext null
        val segueIndexes = tracks.indices.filter { it == 0 || it % spacing == 0 }
        val scheduledSegues = segueIndexes.map { index ->
            JSONObject()
                .put("trackIndex", index)
                .put("kind", if (index == 0) "intro" else "bridge")
                .put("previousSong", tracks.getOrNull(index - 1)?.song?.toJsonSong() ?: JSONObject.NULL)
                .put("targetSong", tracks[index].song.toJsonSong())
        }
        val schema = JSONObject()
            .put("type", "object")
            .put("additionalProperties", false)
            .put(
                "properties",
                JSONObject()
                    .put(
                        "segues",
                        JSONObject()
                            .put("type", "array")
                            .put(
                                "items",
                                JSONObject()
                                    .put("type", "object")
                                    .put("additionalProperties", false)
                                    .put(
                                        "properties",
                                        JSONObject()
                                            .put("trackIndex", JSONObject().put("type", "integer"))
                                            .put("segueText", JSONObject().put("type", "string"))
                                    )
                                    .put("required", JSONArray().put("trackIndex").put("segueText"))
                            )
                    )
            )
            .put("required", JSONArray().put("segues"))
        val input = JSONObject()
            .put("vibeBrief", vibeBrief)
            .put("scheduledSegues", JSONArray(scheduledSegues))
            .put("tracks", JSONArray(tracks.mapIndexed { index, track -> track.toJson(index) }))
            .toString()
        val response = createTextResponse(
            instructions = """
                Write all scheduled DJ segues for this radio playlist in one batch.
                Only write segues for the provided scheduledSegues.
                For kind "intro", introduce the show and the targetSong.
                For kind "bridge", previousSong is the song that just played and targetSong is the song about to play.
                Do not use welcome/show-opening language for bridges.
                Never call targetSong "that was". Never introduce previousSong as the next song.
                Keep bridges quick: "That was X, now here's Y" is allowed, with one small opinion when useful.
                Use the station personality: warm, dryly funny, curious about odd choices, fond of theatrical ambition and emotional sincerity.
                Avoid worn-out hype words: anthemic, anthem, masterpiece, iconic, essential, legendary, epic, classic, gem, banger, perfect fit, absolute must-listen.
                Avoid driving, road, lane, highway, engine, gear, and travel metaphors unless the song title directly calls for it.
                Do not invent facts, chart history, lyrics, or behind-the-scenes anecdotes.
                Return JSON only.
            """.trimIndent(),
            input = input,
            schemaName = "batch_segues",
            schema = schema
        ) ?: return@withContext null
        runCatching<Map<Int, String>?> {
            val segues = JSONObject(response).optJSONArray("segues") ?: return@runCatching null
            val segueMap = mutableMapOf<Int, String>()
            for (i in 0 until segues.length()) {
                val item = segues.optJSONObject(i) ?: continue
                val index = item.optInt("trackIndex", -1)
                val text = item.optString("segueText").trim()
                if (index in tracks.indices && text.isNotBlank()) segueMap[index] = text
            }
            segueMap
        }.getOrNull()
    }

    suspend fun chooseFillIns(
        vibeBrief: String,
        currentPicks: List<BatchPickedSong>,
        candidates: List<Candidate>,
        fillCount: Int
    ): List<BatchPickedSong>? = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || fillCount <= 0 || candidates.isEmpty()) return@withContext null
        val schema = JSONObject()
            .put("type", "object")
            .put("additionalProperties", false)
            .put(
                "properties",
                JSONObject()
                    .put(
                        "picks",
                        JSONObject()
                            .put("type", "array")
                            .put("minItems", fillCount)
                            .put("maxItems", fillCount)
                            .put(
                                "items",
                                JSONObject()
                                    .put("type", "object")
                                    .put("additionalProperties", false)
                                    .put(
                                        "properties",
                                        JSONObject()
                                            .put("candidateId", JSONObject().put("type", "string"))
                                            .put("reason", JSONObject().put("type", "string"))
                                    )
                                    .put("required", JSONArray().put("candidateId").put("reason"))
                            )
                    )
            )
            .put("required", JSONArray().put("picks"))
        val input = JSONObject()
            .put("fillCount", fillCount)
            .put("vibeBrief", vibeBrief)
            .put("currentPicks", JSONArray(currentPicks.mapIndexed { index, pick -> pick.toJson(index) }))
            .put("candidates", JSONArray(candidates.take(96).map { it.toJson() }))
            .toString()
        val response = createTextResponse(
            instructions = """
                Choose replacement songs to fill holes after a radio playlist cleanup removed too many repeat artists.
                Pick exactly fillCount candidate IDs.
                Prefer songs that preserve the target vibe, but be more generous about adjacent fits when the obvious pool is narrow.
                Avoid artists and artistFamily groups already heavily represented in currentPicks unless there are no credible alternatives.
                Adjacent can mean shared theatricality, energy, humor, vocals, production, fantasy/sci-fi flavor, folk/power metal drama, or emotional shape; it does not need to be a literal genre match.
                Do not choose outside the candidate list. Do not use the same candidate twice.
                Return JSON only.
            """.trimIndent(),
            input = input,
            schemaName = "batch_fill_ins",
            schema = schema
        ) ?: return@withContext null
        runCatching<List<BatchPickedSong>?> {
            val picks = JSONObject(response).optJSONArray("picks") ?: return@runCatching null
            val candidateById = candidates.associateBy { it.song.id }
            val used = mutableSetOf<String>()
            val pickedSongs = mutableListOf<BatchPickedSong>()
            for (i in 0 until picks.length()) {
                val pick = picks.optJSONObject(i) ?: continue
                val id = pick.optString("candidateId")
                val candidate = candidateById[id] ?: continue
                if (!used.add(id)) continue
                pickedSongs += BatchPickedSong(candidate.song, pick.optString("reason").trim())
            }
            pickedSongs.takeIf { it.size == fillCount }
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

data class BatchPickedSong(val song: Song, val reason: String)

private fun Candidate.toJson(): JSONObject =
    JSONObject()
        .put("id", song.id)
        .put("title", song.title)
        .put("artist", song.artist)
        .put("artistFamily", ArtistGroups.familyKey(song.artist))
        .put("album", song.album ?: JSONObject.NULL)
        .put("releaseYear", song.releaseYear ?: JSONObject.NULL)
        .put("genreTags", JSONArray(song.genreTags))
        .put("moodTags", JSONArray(song.moodTags))
        .put("stationScore", score)
        .put("stationReasons", JSONArray(reasons))

private fun PlayRecord.toJson(): JSONObject =
    JSONObject()
        .put("title", title)
        .put("artist", artist)
        .put("album", album ?: JSONObject.NULL)

private fun PreparedShowTrack.toJson(index: Int): JSONObject =
    JSONObject()
        .put("index", index)
        .put("title", song.title)
        .put("artist", song.artist)
        .put("artistFamily", ArtistGroups.familyKey(song.artist))
        .put("album", song.album ?: JSONObject.NULL)
        .put("pickReason", pickReason ?: "")

private fun Song.toJsonSong(): JSONObject =
    JSONObject()
        .put("title", title)
        .put("artist", artist)
        .put("album", album ?: JSONObject.NULL)

private fun BatchPickedSong.toJson(index: Int): JSONObject =
    JSONObject()
        .put("index", index)
        .put("title", song.title)
        .put("artist", song.artist)
        .put("artistFamily", ArtistGroups.familyKey(song.artist))
        .put("album", song.album ?: JSONObject.NULL)
        .put("reason", reason)
