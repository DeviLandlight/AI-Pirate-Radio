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
        journeyBeats: List<JourneyBeat> = emptyList(),
        now: Instant = Instant.now()
    ): PreparedShow {
        val catalog = musicRecommender.buildPool(recommendationRequest).songs
        val history = startingHistory.toMutableList()
        val tracks = mutableListOf<PreparedShowTrack>()
        val showTheme = if (recommendationRequest.favoriteArtists.isNotEmpty()) {
            "the requested station vibe"
        } else {
            "a focused set of hand-picked songs"
        }

        var attempt = 0
        while (tracks.size < targetSongCount && attempt < targetSongCount * 4) {
            val index = tracks.size
            val manager = managerForAttempt(tracks.size, targetSongCount, attempt) ?: stationManager
            val selection = manager.selectCandidates(catalog, history, now.plusSeconds(attempt.toLong()))
            attempt += 1
            val showCandidates = selection.candidates
                .filter { candidate -> isAllowedByShowMix(candidate.song, tracks, attempt, targetSongCount) }
                .preferPopularityTierForSlot(index, targetSongCount)
            if (showCandidates.isEmpty()) continue

            val pick = songPicker.pickSong(
                SongPickRequest(
                    stationHistory = startingHistory.takeLast(5),
                    candidates = showCandidates,
                    currentShowSongs = tracks.map { it.song },
                    journeyBeat = journeyBeats.getOrNull(index)
                )
            )
            val selectedSong = pick?.song?.takeIf { picked -> showCandidates.any { it.song.id == picked.id } }
                ?: stationManager.fallbackPick(showCandidates)
                ?: continue

            val playableSong = resolvePlayableSong(selectedSong, showCandidates, tracks) ?: continue
            tracks += PreparedShowTrack(playableSong, null, pick?.reason)
            history += PlayRecord(
                songId = playableSong.id,
                title = playableSong.title,
                artist = playableSong.artist,
                album = playableSong.album,
                genreTags = playableSong.genreTags,
                startedAt = now.plusSeconds(index.toLong() * 240L),
                hadSegue = false
            )
        }

        return PreparedShow(writeSegues(tracks, startingHistory, showTheme, journeyBeats))
    }

    private suspend fun writeSegues(
        tracks: List<PreparedShowTrack>,
        startingHistory: List<PlayRecord>,
        showTheme: String,
        journeyBeats: List<JourneyBeat>
    ): List<PreparedShowTrack> {
        if (tracks.isEmpty()) return tracks
        val showSongs = tracks.map { it.song }
        return tracks.mapIndexed { index, track ->
            val previousTracks = tracks.take(index)
            val segueType = segueTypeFor(index, track.song, previousTracks, startingHistory)
            if (segueType == SegueType.SILENCE) return@mapIndexed track

            val isNewArtist = previousTracks.none { it.song.artist.equals(track.song.artist, ignoreCase = true) } &&
                startingHistory.none { it.artist.equals(track.song.artist, ignoreCase = true) }
            val facts = runCatching { trackFactFinder.factsFor(track.song) }.getOrDefault(emptyList())
            val segue = segueWriter.writeSegue(
                SegueRequest(
                    song = track.song,
                    type = segueType,
                    previousSong = previousTracks.lastOrNull()?.song,
                    showTheme = showTheme,
                    isNewArtist = isNewArtist,
                    verifiedFacts = facts,
                    showSongs = showSongs,
                    journeyBeat = journeyBeats.getOrNull(index),
                    journeyBeats = journeyBeats
                )
            )
            track.copy(segueText = segue?.text)
        }
    }

    private suspend fun resolvePlayableSong(
        preferredSong: Song,
        candidates: List<Candidate>,
        tracks: List<PreparedShowTrack>
    ): Song? {
        val songsToTry = listOf(preferredSong) + candidates.map { it.song }.filterNot { it.id == preferredSong.id }
        for (song in songsToTry.take(MAX_RESOLVE_ATTEMPTS)) {
            val resolved = runCatching { trackResolver.resolve(song) }.getOrNull()
            if (resolved != null) return resolved
        }
        return null
    }

    private companion object {
        const val MAX_RESOLVE_ATTEMPTS = 3
    }
}

private fun List<Candidate>.preferPopularityTierForSlot(index: Int, targetSongCount: Int): List<Candidate> {
    if (isEmpty()) return this
    val preferred = if (isDeepCutSlot(index, targetSongCount)) {
        filter { it.song.isDeepCut() }
    } else {
        filterNot { it.song.isDeepCut() }
    }
    return preferred.takeIf { it.isNotEmpty() } ?: this
}

private fun isDeepCutSlot(index: Int, targetSongCount: Int): Boolean {
    val songNumber = index + 1
    val preferredSlots = if (targetSongCount >= 11) setOf(4, 8, 11) else setOf(4, 8)
    return songNumber in preferredSlots
}

private fun Song.isDeepCut(): Boolean =
    moodTags.any { it.equals("deep_cut", ignoreCase = true) }

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
    val familyCount = tracks.count { ArtistGroups.familyKey(it.song.artist) == ArtistGroups.familyKey(song.artist) }
    val laneCount = tracks.count { ArtistGroups.laneKey(it.song.artist) == ArtistGroups.laneKey(song.artist) }
    val artistCount = tracks.count { it.song.artist.equals(song.artist, ignoreCase = true) }
    val lastResort = attempt >= targetSongCount * 3

    if (!lastResort && artistCount >= 1) return false
    if (lastResort && artistCount >= 3) return false
    if (!lastResort && familyCount >= 1) return false
    if (lastResort && familyCount >= 2) return false
    if (!lastResort && laneCount >= 3) return false

    return true
}

private fun segueTypeFor(index: Int, song: Song, tracks: List<PreparedShowTrack>, history: List<PlayRecord>): SegueType {
    if (index == 0) return SegueType.THEME
    if (index % 2 != 0) return SegueType.SILENCE
    val isNewArtist = tracks.none { it.song.artist.equals(song.artist, ignoreCase = true) } &&
        history.none { it.artist.equals(song.artist, ignoreCase = true) }
    if (index % 7 == 0) return SegueType.FACT
    if (index % 6 == 0) return SegueType.HISTORY
    if (isNewArtist && index % 5 == 0) return SegueType.DISCOVERY
    if (index % 4 == 0) return SegueType.PERSONAL_OBSERVATION
    return SegueType.TRANSITION
}

data class PreparedShow(val tracks: List<PreparedShowTrack>)
data class PreparedShowTrack(val song: Song, val segueText: String?, val pickReason: String?)
