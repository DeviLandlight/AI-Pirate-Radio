package com.aipirateradio.app.station

object ArtistGroups {
    fun familyKey(artist: String): String {
        val normalized = artist.normalizedArtist()
        return when (normalized) {
            "the national", "matt berninger", "el vy" -> "the_national_family"
            "the gaslight anthem", "the horrible crowes", "brian fallon" -> "gaslight_family"
            "pearl jam", "eddie vedder" -> "pearl_jam_family"
            "foo fighters", "dave grohl" -> "foo_fighters_family"
            "the beatles", "john lennon", "paul mccartney", "george harrison", "ringo starr" -> "beatles_family"
            "electric light orchestra", "elo", "jeff lynne", "jeff lynnes elo" -> "elo_family"
            "ayreon", "star one", "arjen anthony lucassen", "the gentle storm", "guilt machine", "ambertian dawn" -> "arjen_lucassen_family"
            "avantasia", "edguy", "tobias sammet" -> "tobias_sammet_family"
            else -> normalized
        }
    }

    fun laneKey(artist: String): String {
        val normalized = artist.normalizedArtist()
        return when (normalized) {
            "the national", "matt berninger", "el vy", "the menzingers", "the gaslight anthem",
            "the horrible crowes", "brian fallon" -> "literary_heartland_indie"
            "pearl jam", "eddie vedder", "soundgarden", "temple of the dog", "alice in chains", "nirvana" -> "grunge_alt_rock"
            "foo fighters", "dave grohl" -> "modern_alt_anthem"
            "ayreon", "star one", "arjen anthony lucassen", "the gentle storm", "guilt machine",
            "avantasia", "edguy", "tobias sammet", "kamelot", "symphony x", "seventh wonder" -> "theatrical_prog_power_metal"
            else -> normalized
        }
    }
}

private fun String.normalizedArtist(): String {
    return lowercase()
        .replace("&", "and")
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()
        .removePrefix("the ")
        .let {
            when (it) {
                "national" -> "the national"
                "gaslight anthem" -> "the gaslight anthem"
                "horrible crowes" -> "the horrible crowes"
                "menzingers" -> "the menzingers"
                "beatles" -> "the beatles"
                else -> it
            }
        }
}
