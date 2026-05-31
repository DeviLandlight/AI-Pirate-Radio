package com.aipirateradio.app.recommendations

import com.aipirateradio.app.station.Song
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
) : MusicRecommender {
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
private fun String.stableId(): String {
    val digest = MessageDigest.getInstance("SHA-1").digest(toByteArray())
    return digest.joinToString("") { "%02x".format(it) }
}
