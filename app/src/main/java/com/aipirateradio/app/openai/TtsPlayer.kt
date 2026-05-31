package com.aipirateradio.app.openai

import android.content.Context
import android.media.MediaPlayer
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume

class AndroidTtsAudioPlayer(
    context: Context,
    private val reportStatus: (String) -> Unit = {}
) : TtsPlayer {
    private val appContext = context.applicationContext

    override suspend fun play(audioBytes: ByteArray?) {
        if (audioBytes == null || audioBytes.isEmpty()) return
        val file = File.createTempFile("segue", ".mp3", appContext.cacheDir)
        file.writeBytes(audioBytes)
        reportStatus("Playing generated DJ segue.")
        suspendCancellableCoroutine { continuation ->
            val player = MediaPlayer()
            continuation.invokeOnCancellation {
                runCatching { player.stop(); player.release() }
                file.delete()
            }
            player.setOnCompletionListener {
                it.release()
                file.delete()
                if (continuation.isActive) continuation.resume(Unit)
            }
            player.setOnErrorListener { mp, _, _ ->
                mp.release()
                file.delete()
                if (continuation.isActive) continuation.resume(Unit)
                true
            }
            runCatching {
                player.setDataSource(file.absolutePath)
                player.prepare()
                player.start()
            }.onFailure {
                player.release()
                file.delete()
                if (continuation.isActive) continuation.resume(Unit)
            }
        }
    }
}
