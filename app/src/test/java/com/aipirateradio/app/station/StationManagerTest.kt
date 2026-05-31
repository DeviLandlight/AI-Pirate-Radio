package com.aipirateradio.app.station

import com.aipirateradio.app.openai.SegueRequest
import com.aipirateradio.app.openai.SegueResult
import com.aipirateradio.app.openai.SegueWriter
import com.aipirateradio.app.openai.SongPickRequest
import com.aipirateradio.app.openai.SongPickResult
import com.aipirateradio.app.openai.SongPicker
import com.aipirateradio.app.playback.TrackResolver
import com.aipirateradio.app.recommendations.MusicRecommender
import com.aipirateradio.app.recommendations.RecommendationPool
import com.aipirateradio.app.recommendations.RecommendationRequest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class StationManagerTest {
    @Test
    fun excludesArtistPlayedWithinHour() {
        val now = Instant.parse("2026-05-29T16:00:00Z")
        val stationManager = StationManager()
        val catalog = listOf(
            Song(id = "1", audioUri = "content://tracks/1", title = "A", artist = "One"),
            Song(id = "2", audioUri = "content://tracks/2", title = "B", artist = "Two")
        )
        val history = listOf(
            PlayRecord(songId = "old", title = "Old", artist = "One", album = null, startedAt = now.minusSeconds(20 * 60))
        )

        val selection = stationManager.selectCandidates(catalog, history, now)

        assertFalse(selection.candidates.any { it.song.artist == "One" })
        assertTrue(selection.candidates.any { it.song.artist == "Two" })
    }

    @Test
    fun excludesRelatedArtistFamilyWithinShowWindow() {
        val now = Instant.parse("2026-05-29T16:00:00Z")
        val stationManager = StationManager()
        val catalog = listOf(
            Song(id = "1", audioUri = "content://tracks/1", title = "A", artist = "Matt Berninger"),
            Song(id = "2", audioUri = "content://tracks/2", title = "B", artist = "Foo Fighters")
        )
        val history = listOf(
            PlayRecord(songId = "old", title = "Old", artist = "The National", album = null, startedAt = now.minusSeconds(20 * 60))
        )

        val selection = stationManager.selectCandidates(catalog, history, now)

        assertFalse(selection.candidates.any { it.song.artist == "Matt Berninger" })
        assertTrue(selection.candidates.any { it.song.artist == "Foo Fighters" })
    }

    @Test
    fun excludesSameArtistAndTitleWithDifferentIdInsideMonthlyWindow() {
        val now = Instant.parse("2026-05-29T16:00:00Z")
        val stationManager = StationManager()
        val catalog = listOf(
            Song(id = "new-id", audioUri = "", title = "Times Like These", artist = "Foo Fighters"),
            Song(id = "other", audioUri = "", title = "Rusty Cage", artist = "Soundgarden")
        )
        val history = listOf(
            PlayRecord(
                songId = "old-id",
                title = "Times Like These",
                artist = "Foo Fighters",
                album = null,
                startedAt = now.minus(Duration.ofDays(2))
            )
        )

        val selection = stationManager.selectCandidates(catalog, history, now)

        assertFalse(selection.candidates.any { it.song.title == "Times Like These" })
        assertTrue(selection.candidates.any { it.song.title == "Rusty Cage" })
    }

    @Test
    fun showPreparationDoesNotRepeatExactArtist() = runTest {
        val songs = listOf(
            Song(id = "foo-1", audioUri = "", title = "One", artist = "Foo Fighters"),
            Song(id = "foo-2", audioUri = "", title = "Two", artist = "Foo Fighters"),
            Song(id = "pj-1", audioUri = "", title = "Three", artist = "Pearl Jam")
        )
        val preparer = ShowPreparer(
            stationManager = StationManager(),
            musicRecommender = FixedRecommender(songs),
            songPicker = FirstPicker(),
            segueWriter = FixedSegueWriter(),
            trackResolver = IdentityResolver()
        )

        val show = preparer.prepareShow(
            recommendationRequest = RecommendationRequest(emptyList()),
            startingHistory = emptyList(),
            startingSegueState = SegueState(),
            targetSongCount = 3
        )

        assertEquals(show.tracks.map { it.song.artist }.distinct(), show.tracks.map { it.song.artist })
    }

    private class FixedRecommender(private val songs: List<Song>) : MusicRecommender {
        override suspend fun buildPool(request: RecommendationRequest): RecommendationPool {
            return RecommendationPool(songs, emptyList())
        }
    }

    private class FirstPicker : SongPicker {
        override suspend fun pickSong(request: SongPickRequest): SongPickResult? {
            return request.candidates.firstOrNull()?.let { SongPickResult(it.song, "first") }
        }
    }

    private class FixedSegueWriter : SegueWriter {
        override suspend fun writeSegue(request: SegueRequest): SegueResult {
            return SegueResult("", "")
        }
    }

    private class IdentityResolver : TrackResolver {
        override suspend fun resolve(song: Song): Song {
            return song
        }
    }
}
