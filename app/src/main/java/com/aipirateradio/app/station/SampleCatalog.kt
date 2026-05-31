package com.aipirateradio.app.station

import java.time.Duration

object SampleCatalog {
    val songs = listOf(
        Song("sample_1", "", "Time Is Running Out", "Muse", "Absolution", 2003, listOf("alt rock", "rock"), listOf("urgent"), Duration.ofMinutes(3).plusSeconds(56)),
        Song("sample_2", "", "Dreams", "Fleetwood Mac", "Rumours", 1977, listOf("classic rock"), listOf("warm"), Duration.ofMinutes(4).plusSeconds(17)),
        Song("sample_3", "", "Smells Like Teen Spirit", "Nirvana", "Nevermind", 1991, listOf("grunge", "rock"), listOf("explosive"), Duration.ofMinutes(5).plusSeconds(1)),
        Song("sample_4", "", "Bohemian Rhapsody", "Queen", "A Night at the Opera", 1975, listOf("classic rock"), listOf("theatrical"), Duration.ofMinutes(5).plusSeconds(55)),
        Song("sample_5", "", "Sweet Child O' Mine", "Guns N' Roses", "Appetite for Destruction", 1987, listOf("hard rock"), listOf("bright"), Duration.ofMinutes(5).plusSeconds(56)),
        Song("sample_6", "", "Superstition", "Stevie Wonder", "Talking Book", 1972, listOf("funk", "soul"), listOf("groove"), Duration.ofMinutes(4).plusSeconds(26)),
        Song("sample_7", "", "Come Together", "The Beatles", "Abbey Road", 1969, listOf("classic rock"), listOf("cool"), Duration.ofMinutes(4).plusSeconds(20)),
        Song("sample_8", "", "Creep", "Radiohead", "Pablo Honey", 1992, listOf("alt rock"), listOf("brooding"), Duration.ofMinutes(3).plusSeconds(58))
    )
}
