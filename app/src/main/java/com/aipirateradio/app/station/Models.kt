package com.aipirateradio.app.station

import java.time.Duration
import java.time.Instant

data class Song(
    val id: String,
    val audioUri: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val releaseYear: Int? = null,
    val genreTags: List<String> = emptyList(),
    val moodTags: List<String> = emptyList(),
    val duration: Duration = Duration.ofMinutes(3),
    val enabled: Boolean = true
)

data class PlayRecord(
    val songId: String,
    val title: String,
    val artist: String,
    val album: String?,
    val genreTags: List<String> = emptyList(),
    val startedAt: Instant,
    val endedAt: Instant? = null,
    val hadSegue: Boolean = false
)

data class StationRules(
    val songRepeatWindow: Duration = Duration.ofDays(30),
    val artistRepeatWindow: Duration = Duration.ofHours(1),
    val artistFamilyRepeatWindow: Duration = Duration.ofHours(2),
    val artistLaneRepeatWindow: Duration = Duration.ofMinutes(24),
    val albumRepeatWindow: Duration = Duration.ofMinutes(45),
    val candidateCount: Int = 6,
    val minSongsBetweenSegues: Int = 2,
    val maxSongsBetweenSegues: Int = 4,
    val enforceSongRepeatWindow: Boolean = true,
    val enforceArtistRepeatWindow: Boolean = true,
    val enforceArtistFamilyRepeatWindow: Boolean = true,
    val enforceArtistLaneRepeatWindow: Boolean = true,
    val enforceAlbumRepeatWindow: Boolean = true,
    val enforcePreviousArtistFamily: Boolean = true
)

data class SegueState(
    val songsSinceLastSegue: Int = 0,
    val didSegueBeforePreviousSong: Boolean = false
)

data class Candidate(
    val song: Song,
    val score: Int,
    val reasons: List<String>
)

data class RejectedSong(
    val song: Song,
    val reason: String
)

data class CandidateSelection(
    val candidates: List<Candidate>,
    val rejected: List<RejectedSong>
)

data class StationDecision(
    val selectedSong: Song,
    val candidates: List<Candidate>,
    val rejected: List<RejectedSong>,
    val llmReason: String?,
    val shouldSegue: Boolean
)
