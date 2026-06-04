package com.aipirateradio.app.openai

import com.aipirateradio.app.station.Candidate
import com.aipirateradio.app.station.JourneyBeat
import com.aipirateradio.app.station.PlayRecord
import com.aipirateradio.app.station.Song

object OpenAiPrompts {
    fun songPickerSystemPrompt(): String = """
        You are choosing the next song for a curated radio station.
        Choose exactly one song from the candidate list.
        Do not choose outside the list.
        Prioritize flow, variety, and listener surprise.
        Prefer higher stationScore unless another candidate clearly creates better radio flow.
        popularityTier "deep_cut" means a less obvious Last.fm track; use it as intentional variety, not as a flaw.
        If journeyBeat is present, treat its description as the current chapter and choose the candidate that best fits that chapter while still respecting flow and stationScore.
        Ask yourself: which song best fits this chapter?
        Do not require a literal title match to the journey label word; emotional or theatrical fit is usually better.
        The station history is older listening context, not necessarily part of the current show.
        In the reason, only mention previous, earlier, follow-up, contrast, or continuity if currentShowSongs is not empty.
        If currentShowSongs is empty, explain why the song works as an opening pick.
        Return JSON only with candidateId and reason.
    """.trimIndent()

    fun songPickerUserPrompt(stationHistory: List<PlayRecord>, currentShowSongs: List<Song>, journeyBeat: JourneyBeat?, candidates: List<Candidate>): String {
        val historyJson = stationHistory.takeLast(5).joinToString(prefix = "[", postfix = "]") {
            """{"title":"${it.title.escapeJson()}","artist":"${it.artist.escapeJson()}"}"""
        }
        val currentShowJson = currentShowSongs.joinToString(prefix = "[", postfix = "]") {
            """{"title":"${it.title.escapeJson()}","artist":"${it.artist.escapeJson()}"}"""
        }
        val candidateJson = candidates.joinToString(prefix = "[", postfix = "]") {
            val song = it.song
            """{"id":"${song.id.escapeJson()}","title":"${song.title.escapeJson()}","artist":"${song.artist.escapeJson()}","album":"${song.album.orEmpty().escapeJson()}","releaseYear":${song.releaseYear ?: "null"},"genreTags":${song.genreTags.toJsonArray()},"moodTags":${song.moodTags.toJsonArray()},"popularityTier":"${song.popularityTier().escapeJson()}","stationScore":${it.score},"stationReasons":${it.reasons.toJsonArray()}}"""
        }
        val beatJson = journeyBeat?.toJsonObject() ?: "null"
        return """{"stationHistory":$historyJson,"currentShowSongs":$currentShowJson,"journeyBeat":$beatJson,"candidates":$candidateJson}"""
    }

    fun seguePrompt(request: SegueRequest): String {
        val song = request.song
        val previous = request.previousSong
        val typeInstruction = when (request.type) {
            SegueType.TRANSITION -> "Write a plain transition. Do not force a reason why the two songs belong together."
            SegueType.FACT -> "Give one concise factual note. If unsure, use a neutral catalog-depth intro."
            SegueType.HISTORY -> "Give brief history/context. If uncertain, avoid specifics."
            SegueType.THEME -> "Introduce the whole show's shared mood based on the current show song list, then connect it lightly to the first song."
            SegueType.DISCOVERY -> "Give one useful discovery note: shared members, side projects, adjacent artists, influence, or 'if you like X, try Y'. If no clear connection, use a simple transition."
            SegueType.PERSONAL_OBSERVATION -> "Write one understated personal observation. Not dramatic, mystical, or factual."
            SegueType.CLOSING -> "Close the show after the final song. Do not introduce another track. Briefly reflect on the set and sign off."
            SegueType.SILENCE -> "Return empty strings."
        }
        return """
            Write a ${request.type.displayName.lowercase()} radio segue.
            Target length: ${request.type.targetSeconds}.
            $typeInstruction
            Sound like an informed human DJ, not marketing copy.
            Give the DJ a point of view: warm, dryly funny, curious about odd choices, and fond of theatrical ambition, emotional sincerity, weird left-field picks, and artists who fully commit to the bit.
            Let the DJ offer small opinions. They can gently tease a song, admit a pick is strange, or say why a moment works. One opinion is enough.
            The show theme may be a planning hint, not a list of artists in the playlist.
            For a theme/intro segue, infer the common thread from Current show songs. Do not overfit to the first song or one artist's gimmick.
            If the list mixes fantasy, pirates, dinosaurs, drinking songs, mining songs, or heroic metal, describe the broader thread as theatrical, goofy, high-energy power/folk metal rather than claiming every band is pirate-themed.
            Do not name artists from the show theme unless that artist is in Current show songs.
            If Journey arc is present, let the DJ segues quietly reveal that arc. Mention the current beat only when it sounds natural.
            Do not make the show sound like a school assignment or announce every beat as "chapter".
            Use only the verified facts below for factual claims. Do not invent recording details, chart history, band history, or behind-the-scenes anecdotes.
            If verified facts are empty or not useful, make a musical observation instead.
            Avoid driving, road, lane, highway, engine, gear, and travel metaphors unless the song title or artist directly calls for it.
            Prefer concrete musical language: texture, tempo, mood, era, production, vocals, guitars, rhythm section, contrast, tension, release, warmth, grit, polish, atmosphere.
            Do not praise every song. Avoid worn-out hype words: anthemic, anthem, masterpiece, iconic, essential, legendary, epic, classic, gem, banger, perfect fit, absolute must-listen.
            Do not call a song underrated unless there is a verified fact supporting that claim.
            Neutral phrasing is allowed: deeper cut, fan favorite, divisive, overlooked, odd little track, not the obvious pick.
            Previous song: ${previous?.let { "${it.artist} - ${it.title}" } ?: "none"}
            Show theme: ${request.showTheme ?: "none provided"}
            New artist for this show: ${request.isNewArtist}
            Current show songs:
            ${request.showSongs.take(16).joinToString("\n") { "- ${it.artist} - ${it.title}" }.ifBlank { "- unknown" }}
            Journey arc:
            ${request.journeyBeats.take(16).joinToString("\n") { "- ${it.number}. ${it.description} (${it.word})" }.ifBlank { "- none" }}
            Current journey chapter: ${request.journeyBeat?.let { "${it.number}. ${it.description} Label=${it.word}; energy=${it.desiredEnergy}; mood=${it.desiredMood}" } ?: "none"}
            Song: ${song.title}
            Artist: ${song.artist}
            Album: ${song.album.orEmpty()}
            Release year: ${song.releaseYear ?: "unknown"}
            Verified facts:
            ${request.verifiedFacts.take(4).joinToString("\n") { "- $it" }.ifBlank { "- none" }}
            Return JSON only with fact and segueText. Use an empty fact string when the segue is not factual.
        """.trimIndent()
    }

    private fun List<String>.toJsonArray(): String = joinToString(prefix = "[", postfix = "]") { """"${it.escapeJson()}"""" }
    private fun Song.popularityTier(): String =
        moodTags.firstOrNull { it.equals("deep_cut", ignoreCase = true) || it.equals("familiar", ignoreCase = true) } ?: "unknown"
    private fun JourneyBeat.toJsonObject(): String =
        """{"number":$number,"chapter":"${description.escapeJson()}","label":"${word.escapeJson()}","desiredEnergy":"${desiredEnergy.escapeJson()}","desiredMood":"${desiredMood.escapeJson()}"}"""
    private fun String.escapeJson(): String = replace("\\", "\\\\").replace("\"", "\\\"")
}
