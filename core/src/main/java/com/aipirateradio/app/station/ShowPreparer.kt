package com.aipirateradio.app.station

import com.aipirateradio.app.openai.SegueRequest
import com.aipirateradio.app.openai.SegueType
import com.aipirateradio.app.openai.SegueWriter
import com.aipirateradio.app.openai.SongPickRequest
import com.aipirateradio.app.openai.SongPicker
import com.aipirateradio.app.playback.TrackResolver
import com.aipirateradio.app.recommendations.MusicRecommender
import com.aipirateradio.app.recommendations.RecommendationRequest
import java.time.Instant

class ShowPreparer(
    private val stationManager: StationManager,
    private val musicRecommender: MusicRecommender,
    private val songPicker: SongPicker,
    private val segueWriter: SegueWriter,
    private val trackResolver: TrackResolver,
    private val trackFactFinder: TrackFactFinder = EmptyTrackFactFinder
) {
    suspend fun prepareShow(
        recommendationRequest: RecommendationRequest,
        startingHistory: List<PlayRecord>,
        startingSegueState: SegueState,
        targetSongCount: Int,
        now: Instant = Instant.now()
    ): PreparedShow {
        val catalog = musicRecommender.buildPool(recommendationRequest).songs
        val history = startingHistory.toMutableList()
        val tracks = mutableListOf<PreparedShowTrack>()
        val showTheme = recommendationRequest.favoriteArtists.take(3).joinToString(", ") { it.name }
            .ifBlank { "songs that can carry a drive without wearing out their welcome" }

        var attempt = 0
        while (tracks.size < targetSongCount && attempt < targetSongCount * 4) {
            val index = tracks.size
            val manager = managerForAttempt(tracks.size, targetSongCount, attempt) ?: stationManager
            val selection = manager.selectCandidates(catalog, history, now.plusSeconds(attempt.toLong()))
            attempt += 1
            val showCandidates = selection.candidates
                .filter { candidate -> isAllowedByShowMix(candidate.song, tracks, attempt, targetSongCount) }
            if (showCandidates.isEmpty()) continue

            val pick = songPicker.pickSong(SongPickRequest(history.takeLast(5), showCandidates))
            val selectedSong = pick?.song?.takeIf { picked -> showCandidates.any { it.song.id == picked.id } }
                ?: stationManager.fallbackPick(showCandidates)
                ?: continue

            val playableSong = resolvePlayableSong(selectedSong, showCandidates, tracks) ?: continue
            val previousSong = tracks.lastOrNull()?.song
            val segueType = segueTypeFor(index, playableSong, tracks, history)
            val isNewArtist = tracks.none { it.song.artist.equals(playableSong.artist, ignoreCase = true) } &&
                history.none { it.artist.equals(playableSong.artist, ignoreCase = true) }
            val segue = if (segueType == SegueType.SILENCE) {
                null
            } else {
                val facts = runCatching { trackFactFinder.factsFor(playableSong) }.getOrDefault(emptyList())
                segueWriter.writeSegue(SegueRequest(playableSong, segueType, previousSong, showTheme, isNewArtist, facts))
            }

            tracks += PreparedShowTrack(playableSong, segue?.text, pick?.reason)
            history += PlayRecord(
                songId = playableSong.id,
                title = playableSong.title,
                artist = playableSong.artist,
                album = playableSong.album,
                genreTags = playableSong.genreTags,
                startedAt = now.plusSeconds(index.toLong() * 240L),
                hadSegue = segue != null
            )
        }

        return PreparedShow(tracks)
    }

    private suspend fun resolvePlayableSong(
        preferredSong: Song,
        candidates: List<Candidate>,
        tracks: List<PreparedShowTrack>
    ): Song? {
        val songsToTry = listOf(preferredSong) + candidates.map { it.song }.filterNot { it.id == preferredSong.id }
        for (song in songsToTry.take(MAX_RESOLVE_ATTEMPTS)) {
            if (tracks.any { it.song.artist.equals(song.artist, ignoreCase = true) }) continue
            val resolved = runCatching { trackResolver.resolve(song) }.getOrNull()
            if (resolved != null && tracks.none { it.song.artist.equals(resolved.artist, ignoreCase = true) }) return resolved
        }
        return null
    }

    private companion object {
        const val MAX_RESOLVE_ATTEMPTS = 3
    }
}

private fun managerForAttempt(currentTrackCount: Int, targetSongCount: Int, attempt: Int): StationManager? {
    val remaining = targetSongCount - currentTrackCount
    val struggling = attempt >= targetSongCount
    val reallyStruggling = attempt >= targetSongCount * 2
    val lastResort = attempt >= targetSongCount * 3
    return when {
        remaining <= 0 -> null
        lastResort -> StationManager(
            StationRules(
                songRepeatWindow = java.time.Duration.ofDays(7),
                artistRepeatWindow = java.time.Duration.ZERO,
                artistFamilyRepeatWindow = java.time.Duration.ZERO,
                artistLaneRepeatWindow = java.time.Duration.ZERO,
                albumRepeatWindow = java.time.Duration.ZERO,
                candidateCount = 12,
                enforceArtistRepeatWindow = false,
                enforceArtistFamilyRepeatWindow = false,
                enforceArtistLaneRepeatWindow = false,
                enforceAlbumRepeatWindow = false,
                enforcePreviousArtistFamily = false
            )
        )
        reallyStruggling || currentTrackCount >= 10 -> StationManager(
            StationRules(
                artistRepeatWindow = java.time.Duration.ZERO,
                artistFamilyRepeatWindow = java.time.Duration.ofMinutes(20),
                artistLaneRepeatWindow = java.time.Duration.ZERO,
                albumRepeatWindow = java.time.Duration.ZERO,
                candidateCount = 12,
                enforceArtistRepeatWindow = false,
                enforceArtistLaneRepeatWindow = false,
                enforceAlbumRepeatWindow = false
            )
        )
        struggling || currentTrackCount >= 8 -> StationManager(
            StationRules(
                artistFamilyRepeatWindow = java.time.Duration.ofMinutes(45),
                artistLaneRepeatWindow = java.time.Duration.ZERO,
                candidateCount = 12,
                enforceArtistLaneRepeatWindow = false
            )
        )
        currentTrackCount >= 6 -> StationManager(
            StationRules(
                artistLaneRepeatWindow = java.time.Duration.ZERO,
                candidateCount = 12,
                enforceArtistLaneRepeatWindow = false
            )
        )
        else -> null
    }
}

private fun isAllowedByShowMix(
    song: Song,
    tracks: List<PreparedShowTrack>,
    attempt: Int,
    targetSongCount: Int
): Boolean {
    if (tracks.any { it.song.artist.equals(song.artist, ignoreCase = true) }) return false

    val familyCount = tracks.count { ArtistGroups.familyKey(it.song.artist) == ArtistGroups.familyKey(song.artist) }
    val laneCount = tracks.count { ArtistGroups.laneKey(it.song.artist) == ArtistGroups.laneKey(song.artist) }
    val lastResort = attempt >= targetSongCount * 3

    if (!lastResort && familyCount >= 1) return false
    if (lastResort && familyCount >= 2) return false
    if (!lastResort && laneCount >= 3) return false

    return true
}

private fun segueTypeFor(index: Int, song: Song, tracks: List<PreparedShowTrack>, history: List<PlayRecord>): SegueType {
    if (index == 0) return SegueType.THEME
    val isNewArtist = tracks.none { it.song.artist.equals(song.artist, ignoreCase = true) } &&
        history.none { it.artist.equals(song.artist, ignoreCase = true) }
    if (index % 4 == 2) return SegueType.SILENCE
    if (index % 7 == 0) return SegueType.FACT
    if (index % 6 == 0) return SegueType.HISTORY
    if (isNewArtist && index % 5 == 0) return SegueType.DISCOVERY
    if (index % 4 == 0) return SegueType.PERSONAL_OBSERVATION
    return SegueType.TRANSITION
}

data class PreparedShow(val tracks: List<PreparedShowTrack>)
data class PreparedShowTrack(val song: Song, val segueText: String?, val pickReason: String?)
