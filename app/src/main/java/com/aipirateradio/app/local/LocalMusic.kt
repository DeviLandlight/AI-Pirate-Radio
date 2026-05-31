package com.aipirateradio.app.local

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.speech.tts.TextToSpeech
import androidx.core.content.ContextCompat
import com.aipirateradio.app.playback.AudioPlayer
import com.aipirateradio.app.playback.TrackResolver
import com.aipirateradio.app.recommendations.MusicRecommender
import com.aipirateradio.app.recommendations.RecommendationPool
import com.aipirateradio.app.recommendations.RecommendationRequest
import com.aipirateradio.app.station.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.time.Duration
import java.util.Locale
import kotlin.coroutines.resume

class LocalMusicLibrary(context: Context) {
    private val appContext = context.applicationContext

    fun hasAudioPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        return ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED
    }

    suspend fun songs(): List<Song> = withContext(Dispatchers.IO) {
        if (!hasAudioPermission()) return@withContext emptyList()
        val projection = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.DURATION)
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val sort = "${MediaStore.Audio.Media.ARTIST} COLLATE NOCASE, ${MediaStore.Audio.Media.TITLE} COLLATE NOCASE"
        appContext.contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection, selection, null, sort)?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            buildList {
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    val uri = Uri.withAppendedPath(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id.toString())
                    val durationMs = cursor.getLong(durationColumn).takeIf { it > 0L } ?: 180_000L
                    add(
                        Song(
                            id = "local_$id",
                            audioUri = uri.toString(),
                            title = cursor.getString(titleColumn)?.takeIf { it.isNotBlank() } ?: "Unknown title",
                            artist = cursor.getString(artistColumn)?.takeIf { it.isNotBlank() } ?: "Unknown artist",
                            album = cursor.getString(albumColumn)?.takeIf { it.isNotBlank() },
                            duration = Duration.ofMillis(durationMs),
                            genreTags = listOf("local")
                        )
                    )
                }
            }
        }.orEmpty()
    }
}

class LocalMusicRecommender(private val library: LocalMusicLibrary) : MusicRecommender {
    override suspend fun buildPool(request: RecommendationRequest): RecommendationPool {
        val songs = library.songs()
        return RecommendationPool(songs, listOf("Local music library: ${songs.size} songs"))
    }
}

class LocalTrackResolver(
    private val library: LocalMusicLibrary? = null,
    private val allowUnmatchedPlannedSongs: Boolean = false
) : TrackResolver {
    override suspend fun resolve(song: Song): Song? {
        if (song.audioUri.startsWith("content://") || song.audioUri.startsWith("file://")) return song
        val localMatch = library?.songs()?.firstOrNull {
            it.title.cleanForMatch() == song.title.cleanForMatch() &&
                it.artist.cleanForMatch() == song.artist.cleanForMatch()
        }
        return localMatch ?: song.takeIf { allowUnmatchedPlannedSongs }
    }

    private fun String.cleanForMatch(): String {
        return lowercase()
            .replace(Regex("\\([^)]*\\)"), "")
            .replace(Regex("\\[[^]]*]"), "")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
    }
}

class AndroidLocalAudioPlayer(
    context: Context,
    private val reportStatus: (String) -> Unit = {}
) : AudioPlayer {
    private val appContext = context.applicationContext
    private var player: MediaPlayer? = null
    private var completionWaiter: ((Unit) -> Unit)? = null

    override suspend fun play(song: Song) {
        stopCurrent()
        val uri = Uri.parse(song.audioUri)
        reportStatus("Playing local file: ${song.artist} - ${song.title}.")
        suspendCancellableCoroutine { continuation ->
            val mediaPlayer = MediaPlayer()
            player = mediaPlayer
            continuation.invokeOnCancellation { stopCurrent() }
            mediaPlayer.setOnPreparedListener {
                it.start()
                if (continuation.isActive) continuation.resume(Unit)
            }
            mediaPlayer.setOnCompletionListener {
                completionWaiter?.invoke(Unit)
                completionWaiter = null
                it.release()
                if (player === it) player = null
            }
            mediaPlayer.setOnErrorListener { mp, _, _ ->
                completionWaiter?.invoke(Unit)
                completionWaiter = null
                mp.release()
                if (player === mp) player = null
                reportStatus("Local playback failed for ${song.title}.")
                if (continuation.isActive) continuation.resume(Unit)
                true
            }
            runCatching {
                mediaPlayer.setDataSource(appContext, uri)
                mediaPlayer.prepareAsync()
            }.onFailure {
                mediaPlayer.release()
                if (player === mediaPlayer) player = null
                reportStatus("Local playback failed: ${it.message ?: it.javaClass.simpleName}.")
                if (continuation.isActive) continuation.resume(Unit)
            }
        }
    }

    override suspend fun pause() {
        player?.pause()
    }

    override suspend fun awaitTrackEnd(song: Song) {
        val activePlayer = player ?: return delay(song.duration.toMillis())
        suspendCancellableCoroutine { continuation ->
            completionWaiter = { if (continuation.isActive) continuation.resume(Unit) }
            continuation.invokeOnCancellation {
                completionWaiter = null
                runCatching { activePlayer.stop(); activePlayer.release() }
                if (player === activePlayer) player = null
            }
        }
    }

    private fun stopCurrent() {
        completionWaiter?.invoke(Unit)
        completionWaiter = null
        runCatching { player?.stop(); player?.release() }
        player = null
    }
}

class AndroidLocalDjAnnouncer(
    context: Context,
    private val reportStatus: (String) -> Unit = {}
) {
    private val appContext = context.applicationContext
    private var tts: TextToSpeech? = null

    suspend fun speak(text: String?) {
        if (text.isNullOrBlank()) return
        val engine = getEngine() ?: return
        reportStatus("DJ: $text")
        suspendCancellableCoroutine { continuation ->
            val utteranceId = "dj-${System.currentTimeMillis()}"
            engine.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) {
                    if (continuation.isActive) continuation.resume(Unit)
                }
                @Deprecated("Deprecated in Android")
                override fun onError(utteranceId: String?) {
                    if (continuation.isActive) continuation.resume(Unit)
                }
            })
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            continuation.invokeOnCancellation { engine.stop() }
        }
    }

    private suspend fun getEngine(): TextToSpeech? {
        tts?.let { return it }
        return suspendCancellableCoroutine { continuation ->
            var engine: TextToSpeech? = null
            engine = TextToSpeech(appContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    engine?.language = Locale.US
                    tts = engine
                    if (continuation.isActive) continuation.resume(engine)
                } else {
                    reportStatus("Offline DJ voice is not available on this device.")
                    if (continuation.isActive) continuation.resume(null)
                }
            }
        }
    }
}
