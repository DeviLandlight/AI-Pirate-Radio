package com.aipirateradio.bot

import com.aipirateradio.app.playback.TrackResolver
import com.aipirateradio.app.recommendations.MusicRecommender
import com.aipirateradio.app.recommendations.RecommendationPool
import com.aipirateradio.app.recommendations.RecommendationRequest
import com.aipirateradio.app.station.Song
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.nameWithoutExtension

class DesktopMusicLibrary(
    private val root: Path?
) {
    fun songs(): List<Song> {
        val libraryRoot = root ?: return emptyList()
        if (!Files.isDirectory(libraryRoot)) return emptyList()
        return Files.walk(libraryRoot).use { stream ->
            stream
                .filter { it.isRegularFile() }
                .filter { it.extension.lowercase() in AUDIO_EXTENSIONS }
                .map { it.toSong() }
                .toList()
        }
    }

    private fun Path.toSong(): Song {
        val (artist, title) = parseArtistTitle(nameWithoutExtension)
        return Song(
            id = "desktop:${toAbsolutePath()}".stableId(),
            audioUri = toUri().toString(),
            title = title,
            artist = artist,
            duration = Duration.ofMinutes(3),
            genreTags = listOf("desktop"),
            moodTags = emptyList()
        )
    }

    private fun parseArtistTitle(name: String): Pair<String, String> {
        val cleaned = name.replace('_', ' ').trim()
        val parts = cleaned.split(" - ", limit = 2)
        return if (parts.size == 2) {
            parts[0].ifBlank { "Unknown artist" } to parts[1].ifBlank { cleaned }
        } else {
            "Unknown artist" to cleaned.ifBlank { "Unknown title" }
        }
    }

    private companion object {
        val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "aac", "flac", "ogg", "opus", "wav", "aiff", "aif")
    }
}

class DesktopMusicRecommender(
    private val library: DesktopMusicLibrary
) : MusicRecommender {
    override suspend fun buildPool(request: RecommendationRequest): RecommendationPool {
        val songs = library.songs()
        return RecommendationPool(songs, listOf("Desktop music library: ${songs.size} songs"))
    }
}

class DesktopTrackResolver(
    private val library: DesktopMusicLibrary,
    private val allowUnmatchedPlannedSongs: Boolean = false
) : TrackResolver {
    override suspend fun resolve(song: Song): Song? {
        if (song.audioUri.startsWith("file://")) return song
        val localMatch = library.songs().firstOrNull {
            it.title.cleanForMatch() == song.title.cleanForMatch() &&
                it.artist.cleanForMatch() == song.artist.cleanForMatch()
        }
        return localMatch ?: song.takeIf { allowUnmatchedPlannedSongs }
    }
}

fun Song.localPathOrNull(): Path? {
    if (!audioUri.startsWith("file://")) return null
    return runCatching { Path.of(URI(audioUri)) }.getOrNull()
}

private fun String.cleanForMatch(): String {
    return lowercase()
        .replace(Regex("\\([^)]*\\)"), "")
        .replace(Regex("\\[[^]]*]"), "")
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()
}

private fun String.stableId(): String {
    val digest = MessageDigest.getInstance("SHA-1").digest(toByteArray())
    return digest.joinToString("") { "%02x".format(it) }
}
