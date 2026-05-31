package com.aipirateradio.app.openai

import com.aipirateradio.app.station.Candidate
import com.aipirateradio.app.station.PlayRecord
import com.aipirateradio.app.station.Song

data class SongPickRequest(val previousSongs: List<PlayRecord>, val candidates: List<Candidate>)
data class SongPickResult(val song: Song, val reason: String)
data class SegueResult(val fact: String, val text: String, val audioBytes: ByteArray? = null)

enum class SegueType(val displayName: String, val targetSeconds: String) {
    TRANSITION("Transition", "about 5 seconds"),
    FACT("Fact", "8 to 12 seconds"),
    HISTORY("History", "10 to 15 seconds"),
    THEME("Theme", "15 to 20 seconds"),
    DISCOVERY("Discovery", "about 10 seconds"),
    PERSONAL_OBSERVATION("Personal observation", "about 8 seconds"),
    SILENCE("Silence", "0 seconds")
}

data class SegueRequest(
    val song: Song,
    val type: SegueType = SegueType.TRANSITION,
    val previousSong: Song? = null,
    val showTheme: String? = null,
    val isNewArtist: Boolean = false
)

interface SongPicker {
    suspend fun pickSong(request: SongPickRequest): SongPickResult?
}

interface SegueWriter {
    suspend fun writeSegue(request: SegueRequest): SegueResult?
    suspend fun writeSegue(song: Song): SegueResult? = writeSegue(SegueRequest(song = song))
}

class FallbackSongPicker(
    private val primary: SongPicker,
    private val fallback: SongPicker,
    private val reportStatus: (String) -> Unit = {}
) : SongPicker {
    override suspend fun pickSong(request: SongPickRequest): SongPickResult? {
        val result = runCatching { primary.pickSong(request) }
            .onFailure { reportStatus("OpenAI DJ pick failed: ${it.readableMessage()}. Using local picker.") }
            .getOrNull()
        return if (result != null) {
            reportStatus("OpenAI DJ picked ${result.song.artist} - ${result.song.title}.")
            result
        } else {
            reportStatus("OpenAI DJ pick failed. Using local picker.")
            fallback.pickSong(request)
        }
    }
}

class FallbackSegueWriter(
    private val primary: SegueWriter,
    private val fallback: SegueWriter,
    private val reportStatus: (String) -> Unit = {}
) : SegueWriter {
    override suspend fun writeSegue(request: SegueRequest): SegueResult? {
        val result = runCatching { primary.writeSegue(request) }
            .onFailure { reportStatus("OpenAI DJ segue failed: ${it.readableMessage()}. Using local segue.") }
            .getOrNull()
        return result ?: fallback.writeSegue(request)
    }
}

class LocalSongPicker : SongPicker {
    override suspend fun pickSong(request: SongPickRequest): SongPickResult? {
        val candidate = request.candidates.maxByOrNull { it.score } ?: return null
        return SongPickResult(candidate.song, "Local picker chose the strongest scored candidate.")
    }
}

class LocalSegueWriter : SegueWriter {
    override suspend fun writeSegue(request: SegueRequest): SegueResult {
        val song = request.song
        val previous = request.previousSong
        val album = song.album?.let { " off $it" }.orEmpty()
        val year = song.releaseYear?.let { " from $it" }.orEmpty()
        val text = when (request.type) {
            SegueType.TRANSITION -> if (previous == null) {
                "Let's start this stretch with ${song.artist} and ${song.title}$album$year."
            } else {
                "From ${previous.artist}, let's shift into ${song.artist} with ${song.title}."
            }
            SegueType.FACT -> "A little deeper into the set now: ${song.artist}, with ${song.title}$album$year."
            SegueType.HISTORY -> "This next one sits a little deeper in the catalog. Here's ${song.artist} with ${song.title}."
            SegueType.THEME -> "Tonight's show is circling ${request.showTheme ?: "songs that carry a drive"}. First up, ${song.artist} with ${song.title}."
            SegueType.DISCOVERY -> "If ${previous?.artist ?: "the last artist"} is in your lane, keep an ear on this next one: ${song.artist}, ${song.title}."
            SegueType.PERSONAL_OBSERVATION -> "This one feels like a late-night highway song: ${song.artist}, ${song.title}."
            SegueType.SILENCE -> ""
        }
        return SegueResult("", text)
    }
}

private fun Throwable.readableMessage(): String = message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName
