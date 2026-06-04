package com.aipirateradio.app.station

import com.aipirateradio.app.openai.SegueRequest
import com.aipirateradio.app.openai.SegueType
import com.aipirateradio.app.openai.SegueWriter
import com.aipirateradio.app.openai.SongPickRequest
import com.aipirateradio.app.openai.SongPicker
import com.aipirateradio.app.openai.TtsPlayer
import com.aipirateradio.app.playback.AudioPlayer
import com.aipirateradio.app.playback.TrackResolver
import com.aipirateradio.app.recommendations.MusicRecommender
import com.aipirateradio.app.recommendations.RecommendationRequest
import java.time.Instant

class StationEngine(
    private val stationManager: StationManager,
    private val musicRecommender: MusicRecommender,
    private val songPicker: SongPicker,
    private val segueWriter: SegueWriter,
    private val ttsPlayer: TtsPlayer,
    private val trackResolver: TrackResolver,
    private val audioPlayer: AudioPlayer
) {
    suspend fun chooseAndPlayNext(
        recommendationRequest: RecommendationRequest,
        history: List<PlayRecord>,
        segueState: SegueState,
        now: Instant = Instant.now()
    ): StationDecision? {
        val catalog = musicRecommender.buildPool(recommendationRequest).songs
        val selection = stationManager.selectCandidates(catalog, history, now)
        if (selection.candidates.isEmpty()) return null
        val pick = songPicker.pickSong(SongPickRequest(stationHistory = history.takeLast(5), candidates = selection.candidates))
        val selectedSong = pick?.song?.takeIf { picked -> selection.candidates.any { it.song.id == picked.id } }
            ?: stationManager.fallbackPick(selection.candidates)
            ?: return null
        val playableSong = trackResolver.resolve(selectedSong) ?: return null
        val shouldSegue = stationManager.shouldSegue(segueState)
        if (shouldSegue) {
            val previousSong = history.lastOrNull()?.let {
                Song(it.songId, "", it.title, it.artist, it.album, genreTags = it.genreTags)
            }
            val segue = segueWriter.writeSegue(SegueRequest(playableSong, SegueType.TRANSITION, previousSong))
            ttsPlayer.play(segue?.audioBytes)
        }
        audioPlayer.play(playableSong)
        return StationDecision(playableSong, selection.candidates, selection.rejected, pick?.reason, shouldSegue)
    }
}
