package com.aipirateradio.app.recommendations

import com.aipirateradio.app.station.Song
import com.aipirateradio.app.station.TrackFactFinder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.security.MessageDigest
import java.time.Duration

class LastFmClient(
    private val apiKeyProvider: () -> String,
    private val httpClient: OkHttpClient = OkHttpClient()
) : MusicRecommender, TrackFactFinder {
    override suspend fun buildPool(request: RecommendationRequest): RecommendationPool = withContext(Dispatchers.IO) {
        val apiKey = apiKeyProvider().trim()
        if (apiKey.isBlank()) return@withContext RecommendationPool(emptyList(), listOf("Missing Last.fm API key"))

        val artists = request.favoriteArtists.flatMap { seed ->
            listOf(seed.name) + similarArtists(apiKey, seed.name).take(request.maxArtistsPerSeed)
        }.distinctBy { it.lowercase() }

        val songs = artists.flatMap { artist ->
            topTracks(apiKey, artist).take(request.maxTracksPerArtist)
        }.distinctBy { "${it.artist.lowercase()}|${it.title.lowercase()}" }

        RecommendationPool(songs, listOf("Last.fm: ${songs.size} songs from ${artists.size} artists"))
    }

    override suspend fun factsFor(song: Song): List<String> = withContext(Dispatchers.IO) {
        val apiKey = apiKeyProvider().trim()
        if (apiKey.isBlank()) return@withContext emptyList()
        buildList {
            val trackInfo = trackInfo(apiKey, song.artist, song.title)
            trackInfo?.optJSONObject("track")?.let { track ->
                track.optJSONObject("album")?.optString("title")?.takeIf { it.isNotBlank() && song.album.isNullOrBlank() }?.let {
                    add("Last.fm lists the track on the album $it.")
                }
                track.optJSONObject("wiki")?.optString("summary").cleanLastFmSummary()?.let { add(it) }
            }
            artistInfo(apiKey, song.artist)
                ?.optJSONObject("artist")
                ?.optJSONObject("bio")
                ?.optString("summary")
                .cleanLastFmSummary()
                ?.let { add(it) }
        }.distinct().take(3)
    }

    suspend fun topSongsForArtist(artist: String, limit: Int = 8): List<Song> = withContext(Dispatchers.IO) {
        val apiKey = apiKeyProvider().trim()
        if (apiKey.isBlank() || artist.isBlank()) return@withContext emptyList()
        topTracks(apiKey, artist).take(limit)
    }

    private fun similarArtists(apiKey: String, artist: String): List<String> {
        val json = getJson("https://ws.audioscrobbler.com/2.0/?method=artist.getsimilar&artist=${artist.url()}&api_key=$apiKey&format=json&limit=8")
        val array = json?.optJSONObject("similarartists")?.optJSONArray("artist") ?: return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                array.optJSONObject(i)?.optString("name")?.takeIf { it.isNotBlank() }?.let { add(it) }
            }
        }
    }

    private fun topTracks(apiKey: String, artist: String): List<Song> {
        val json = getJson("https://ws.audioscrobbler.com/2.0/?method=artist.gettoptracks&artist=${artist.url()}&api_key=$apiKey&format=json&limit=12")
        val array = json?.optJSONObject("toptracks")?.optJSONArray("track") ?: return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val track = array.optJSONObject(i) ?: continue
                val title = track.optString("name").takeIf { it.isNotBlank() } ?: continue
                val trackArtist = track.optJSONObject("artist")?.optString("name").takeIf { !it.isNullOrBlank() } ?: artist
                add(
                    Song(
                        id = "lastfm:$trackArtist:$title".stableId(),
                        audioUri = "",
                        title = title,
                        artist = trackArtist,
                        duration = Duration.ofMinutes(3),
                        genreTags = listOf("lastfm"),
                        moodTags = emptyList()
                    )
                )
            }
        }
    }

    private fun trackInfo(apiKey: String, artist: String, track: String): JSONObject? =
        getJson("https://ws.audioscrobbler.com/2.0/?method=track.getInfo&artist=${artist.url()}&track=${track.url()}&api_key=$apiKey&format=json&autocorrect=1")

    private fun artistInfo(apiKey: String, artist: String): JSONObject? =
        getJson("https://ws.audioscrobbler.com/2.0/?method=artist.getInfo&artist=${artist.url()}&api_key=$apiKey&format=json&autocorrect=1")

    private fun getJson(url: String): JSONObject? {
        val request = Request.Builder().url(url).build()
        return runCatching {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                JSONObject(response.body?.string().orEmpty())
            }
        }.getOrNull()
    }
}

private fun String.url(): String = java.net.URLEncoder.encode(this, Charsets.UTF_8.name())
private fun String?.cleanLastFmSummary(): String? {
    val cleaned = this
        ?.replace(Regex("<a\\b[^>]*>.*?</a>", RegexOption.IGNORE_CASE), "")
        ?.replace(Regex("<[^>]+>"), "")
        ?.replace(Regex("\\s+"), " ")
        ?.trim()
        ?.removeSuffix("Read more on Last.fm.")
        ?.trim()
        .orEmpty()
    if (cleaned.length < 30) return null
    val firstSentence = Regex("(.+?[.!?])\\s").find("$cleaned ")?.groupValues?.getOrNull(1) ?: cleaned
    return firstSentence.take(220).trim()
}

private fun String.stableId(): String {
    val digest = MessageDigest.getInstance("SHA-1").digest(toByteArray())
    return digest.joinToString("") { "%02x".format(it) }
}
