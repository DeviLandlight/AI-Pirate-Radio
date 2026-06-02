package com.aipirateradio.app.station

interface TrackFactFinder {
    suspend fun factsFor(song: Song): List<String>
}

object EmptyTrackFactFinder : TrackFactFinder {
    override suspend fun factsFor(song: Song): List<String> = emptyList()
}
