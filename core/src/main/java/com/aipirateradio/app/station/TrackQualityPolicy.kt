package com.aipirateradio.app.station

object TrackQualityPolicy {
    fun rejectionReason(song: Song): String? {
        val titleKey = song.title.cleanQualityKey()
        val artistKey = song.artist.cleanQualityKey()
        if (titleKey.isBlank()) return "Missing song title"
        if (artistKey.isBlank()) return "Missing artist"

        val unknownArtist = artistKey in UNKNOWN_ARTIST_KEYS
        val placeholderTitle = PLACEHOLDER_TITLE_PATTERNS.any { it.matches(titleKey) }
        if (unknownArtist && placeholderTitle) return "Unidentified placeholder track"
        if (unknownArtist && titleKey in UNKNOWN_TITLE_KEYS) return "Unidentified placeholder track"
        return null
    }

    fun isPlayableCatalogTrack(song: Song): Boolean = rejectionReason(song) == null

    private fun String.cleanQualityKey(): String =
        lowercase()
            .replace(Regex("\\([^)]*\\)"), "")
            .replace(Regex("\\[[^]]*]"), "")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()

    private val UNKNOWN_ARTIST_KEYS = setOf(
        "unknown",
        "unknown artist",
        "unknown artists",
        "various artists",
        "no artist",
        "untitled artist"
    )

    private val UNKNOWN_TITLE_KEYS = setOf(
        "unknown",
        "unknown title",
        "untitled",
        "untitled track",
        "audio",
        "song"
    )

    private val PLACEHOLDER_TITLE_PATTERNS = listOf(
        Regex("""track\s*\d{1,3}"""),
        Regex("""pista\s*\d{1,3}"""),
        Regex("""audio\s*\d{1,3}"""),
        Regex("""song\s*\d{1,3}"""),
        Regex("""untitled\s*\d{0,3}"""),
        Regex("""disc\s*\d{1,3}""")
    )
}
