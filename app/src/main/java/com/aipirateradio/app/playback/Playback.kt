package com.aipirateradio.app.playback

import com.aipirateradio.app.station.Song

interface TrackResolver {
    suspend fun resolve(song: Song): Song?
}

interface AudioPlayer {
    suspend fun play(song: Song)
    suspend fun pause()
    suspend fun awaitTrackEnd(song: Song) = Unit
}
