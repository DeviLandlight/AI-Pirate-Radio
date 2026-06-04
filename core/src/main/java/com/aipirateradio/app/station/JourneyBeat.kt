package com.aipirateradio.app.station

data class JourneyBeat(
    val number: Int,
    val word: String,
    val description: String,
    val desiredEnergy: String = "",
    val desiredMood: String = ""
)
