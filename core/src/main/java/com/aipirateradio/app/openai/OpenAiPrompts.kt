package com.aipirateradio.app.openai

import com.aipirateradio.app.station.Candidate
import com.aipirateradio.app.station.PlayRecord

object OpenAiPrompts {
    fun songPickerSystemPrompt(): String = """
        You are choosing the next song for a curated radio station.
        Choose exactly one song from the candidate list.
        Do not choose outside the list.
        Prioritize flow, variety, and listener surprise.
        Return JSON only with candidateId and reason.
    """.trimIndent()

    fun songPickerUserPrompt(previousSongs: List<PlayRecord>, candidates: List<Candidate>): String {
        val previousJson = previousSongs.takeLast(5).joinToString(prefix = "[", postfix = "]") {
            """{"title":"${it.title.escapeJson()}","artist":"${it.artist.escapeJson()}"}"""
        }
        val candidateJson = candidates.joinToString(prefix = "[", postfix = "]") {
            val song = it.song
            """{"id":"${song.id.escapeJson()}","title":"${song.title.escapeJson()}","artist":"${song.artist.escapeJson()}","album":"${song.album.orEmpty().escapeJson()}","releaseYear":${song.releaseYear ?: "null"},"genreTags":${song.genreTags.toJsonArray()},"moodTags":${song.moodTags.toJsonArray()}}"""
        }
        return """{"previousSongs":$previousJson,"candidates":$candidateJson}"""
    }

    fun seguePrompt(request: SegueRequest): String {
        val song = request.song
        val previous = request.previousSong
        val typeInstruction = when (request.type) {
            SegueType.TRANSITION -> "Write a plain transition. Do not force a reason why the two songs belong together."
            SegueType.FACT -> "Give one concise factual note. If unsure, use a neutral catalog-depth intro."
            SegueType.HISTORY -> "Give brief history/context. If uncertain, avoid specifics."
            SegueType.THEME -> "Introduce the show's theme and connect it lightly to the first song."
            SegueType.DISCOVERY -> "Give one useful discovery note: shared members, side projects, adjacent artists, influence, or 'if you like X, try Y'. If no clear connection, use a simple transition."
            SegueType.PERSONAL_OBSERVATION -> "Write one understated personal observation. Not dramatic, mystical, or factual."
            SegueType.SILENCE -> "Return empty strings."
        }
        return """
            Write a ${request.type.displayName.lowercase()} radio segue.
            Target length: ${request.type.targetSeconds}.
            $typeInstruction
            Sound like an informed human DJ, not marketing copy.
            Do not say every song is a gem, classic, anthem, masterpiece, journey, or perfect fit.
            Neutral phrasing is allowed: deeper cut, fan favorite, divisive, overlooked, odd little track, not the obvious pick.
            Previous song: ${previous?.let { "${it.artist} - ${it.title}" } ?: "none"}
            Show theme: ${request.showTheme ?: "none provided"}
            New artist for this show: ${request.isNewArtist}
            Song: ${song.title}
            Artist: ${song.artist}
            Album: ${song.album.orEmpty()}
            Release year: ${song.releaseYear ?: "unknown"}
            Return JSON only with fact and segueText. Use an empty fact string when the segue is not factual.
        """.trimIndent()
    }

    private fun List<String>.toJsonArray(): String = joinToString(prefix = "[", postfix = "]") { """"${it.escapeJson()}"""" }
    private fun String.escapeJson(): String = replace("\\", "\\\\").replace("\"", "\\\"")
}
