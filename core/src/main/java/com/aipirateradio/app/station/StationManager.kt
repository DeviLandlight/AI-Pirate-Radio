package com.aipirateradio.app.station

import java.time.Instant
import kotlin.math.max
import kotlin.random.Random

class StationManager(
    private val rules: StationRules = StationRules(),
    private val random: Random = Random.Default
) {
    fun selectCandidates(
        catalog: List<Song>,
        history: List<PlayRecord>,
        now: Instant = Instant.now()
    ): CandidateSelection {
        val rejected = mutableListOf<RejectedSong>()
        val eligible = catalog.filter { song ->
            val reason = rejectionReason(song, history, now)
            if (reason != null) {
                rejected += RejectedSong(song, reason)
                false
            } else {
                true
            }
        }

        val candidates = eligible
            .map { song -> scoreSong(song, history, now) }
            .sortedWith(compareByDescending<Candidate> { it.score }.thenBy { it.song.artist })
            .take(max(1, rules.candidateCount * 2))
            .shuffled(random)
            .sortedByDescending { it.score + random.nextInt(0, 8) }
            .take(rules.candidateCount)

        return CandidateSelection(candidates = candidates, rejected = rejected)
    }

    fun shouldSegue(state: SegueState): Boolean {
        if (state.didSegueBeforePreviousSong) return false
        if (state.songsSinceLastSegue < rules.minSongsBetweenSegues) return false
        if (state.songsSinceLastSegue >= rules.maxSongsBetweenSegues) return true
        return random.nextFloat() < 0.35f
    }

    fun fallbackPick(candidates: List<Candidate>): Song? = candidates.maxByOrNull { it.score }?.song

    fun rejectionReason(song: Song, history: List<PlayRecord>, now: Instant = Instant.now()): String? {
        if (!song.enabled) return "Song is disabled"
        TrackQualityPolicy.rejectionReason(song)?.let { return it }
        SeasonalMusicPolicy.rejectionReason(song, now)?.let { return it }

        val lastSongPlay = history.filter { it.songId == song.id || it.songKey() == song.songKey() }.maxByOrNull { it.startedAt }
        if (rules.enforceSongRepeatWindow && lastSongPlay != null && lastSongPlay.startedAt > now.minus(rules.songRepeatWindow)) return "Song played inside repeat window"

        val lastArtistPlay = history.filter { it.artist.equals(song.artist, ignoreCase = true) }.maxByOrNull { it.startedAt }
        if (rules.enforceArtistRepeatWindow && lastArtistPlay != null && lastArtistPlay.startedAt > now.minus(rules.artistRepeatWindow)) return "Artist played inside hourly artist window"

        val songFamily = ArtistGroups.familyKey(song.artist)
        val lastFamilyPlay = history.filter { ArtistGroups.familyKey(it.artist) == songFamily }.maxByOrNull { it.startedAt }
        if (rules.enforceArtistFamilyRepeatWindow && lastFamilyPlay != null && lastFamilyPlay.startedAt > now.minus(rules.artistFamilyRepeatWindow)) return "Related artist family played too recently"

        val songLane = ArtistGroups.laneKey(song.artist)
        val lastLanePlay = history.filter { ArtistGroups.laneKey(it.artist) == songLane }.maxByOrNull { it.startedAt }
        if (rules.enforceArtistLaneRepeatWindow && lastLanePlay != null && lastLanePlay.startedAt > now.minus(rules.artistLaneRepeatWindow)) return "Similar artist lane played too recently"

        val lastAlbumPlay = history.filter { it.album != null && it.album.equals(song.album, ignoreCase = true) }.maxByOrNull { it.startedAt }
        if (rules.enforceAlbumRepeatWindow && song.album != null && lastAlbumPlay != null && lastAlbumPlay.startedAt > now.minus(rules.albumRepeatWindow)) return "Album played too recently"

        val previous = history.maxByOrNull { it.startedAt }
        if (previous != null && previous.artist.equals(song.artist, ignoreCase = true)) return "Same artist as previous song"
        if (rules.enforcePreviousArtistFamily && previous != null && ArtistGroups.familyKey(previous.artist) == ArtistGroups.familyKey(song.artist)) return "Related artist family as previous song"

        return null
    }

    private fun scoreSong(song: Song, history: List<PlayRecord>, now: Instant): Candidate {
        var score = 50
        val reasons = mutableListOf<String>()

        val lastSongPlay = history.filter { it.songId == song.id || it.songKey() == song.songKey() }.maxByOrNull { it.startedAt }
        if (lastSongPlay == null) {
            score += 40
            reasons += "Never played"
        } else {
            val hoursAgo = java.time.Duration.between(lastSongPlay.startedAt, now).toHours()
            score += minOf(30, hoursAgo.toInt())
            reasons += "Song rested for ${hoursAgo}h"
        }

        val lastArtistPlay = history.filter { it.artist.equals(song.artist, ignoreCase = true) }.maxByOrNull { it.startedAt }
        if (lastArtistPlay == null) {
            score += 20
            reasons += "Fresh artist"
        } else {
            val hoursAgo = java.time.Duration.between(lastArtistPlay.startedAt, now).toHours()
            score += minOf(20, hoursAgo.toInt() / 2)
            reasons += "Artist rested for ${hoursAgo}h"
        }

        val sameLaneCount = history.count { ArtistGroups.laneKey(it.artist) == ArtistGroups.laneKey(song.artist) }
        if (sameLaneCount > 0) {
            score -= minOf(30, sameLaneCount * 10)
            reasons += "Similar artist lane already represented"
        }

        val previous = history.maxByOrNull { it.startedAt }
        if (previous != null && song.genreTags.intersect(previous.genreTags.toSet()).isEmpty()) {
            score += 8
            reasons += "Genre shift"
        }

        score += random.nextInt(0, 6)
        return Candidate(song = song, score = score, reasons = reasons)
    }
}

private fun Song.songKey(): String = "${artist.cleanSongKey()}|${title.cleanSongKey()}"
private fun PlayRecord.songKey(): String = "${artist.cleanSongKey()}|${title.cleanSongKey()}"
private fun String.cleanSongKey(): String {
    return lowercase()
        .replace(Regex("\\([^)]*\\)"), "")
        .replace(Regex("\\[[^]]*]"), "")
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()
}
