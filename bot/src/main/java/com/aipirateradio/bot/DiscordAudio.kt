package com.aipirateradio.bot

import com.aipirateradio.app.station.Song
import net.dv8tion.jda.api.audio.AudioSendHandler
import java.io.BufferedInputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class DiscordPcmSendHandler : AudioSendHandler {
    private val queue = ArrayBlockingQueue<ByteArray>(BUFFER_CHUNKS)

    override fun canProvide(): Boolean = queue.isNotEmpty()

    override fun provide20MsAudio(): ByteBuffer? {
        val chunk = queue.poll() ?: return null
        return ByteBuffer.wrap(chunk)
    }

    override fun isOpus(): Boolean = false

    fun offer(chunk: ByteArray, stopped: AtomicBoolean): Boolean {
        while (!stopped.get()) {
            if (queue.offer(chunk, 100, TimeUnit.MILLISECONDS)) return true
        }
        return false
    }

    fun clear() {
        queue.clear()
    }

    private companion object {
        const val BUFFER_CHUNKS = 250
    }
}

class DiscordAudioPlayer(
    private val sendHandler: DiscordPcmSendHandler
) {
    private val stopped = AtomicBoolean(false)

    fun stop() {
        stopped.set(true)
        sendHandler.clear()
    }

    fun resetForPlayback() {
        stopped.set(false)
        sendHandler.clear()
    }

    fun isStopped(): Boolean = stopped.get()

    fun play(song: Song): Boolean {
        val path = song.localPathOrNull() ?: return false
        if (!Files.isRegularFile(path)) return false
        return if (isFfmpegAvailable()) {
            playWithFfmpeg(path.toString())
        } else {
            false
        }
    }

    fun isFfmpegAvailable(): Boolean {
        return runCatching {
            ProcessBuilder("ffmpeg", "-version").start().waitFor(3, TimeUnit.SECONDS)
        }.getOrDefault(false)
    }

    private fun playWithFfmpeg(path: String): Boolean {
        val process = ProcessBuilder(
            "ffmpeg",
            "-hide_banner",
            "-loglevel",
            "error",
            "-i",
            path,
            "-f",
            "s16be",
            "-ar",
            "48000",
            "-ac",
            "2",
            "pipe:1"
        ).redirectErrorStream(true).start()

        BufferedInputStream(process.inputStream).use { input ->
            val buffer = ByteArray(PCM_20_MS_BYTES)
            while (!stopped.get()) {
                val read = input.readFullyOrPartial(buffer)
                if (read <= 0) break
                val chunk = if (read == buffer.size) buffer.copyOf() else buffer.copyOf(read).paddedPcmChunk()
                if (!sendHandler.offer(chunk, stopped)) break
            }
        }
        if (stopped.get()) process.destroyForcibly() else process.waitFor(2, TimeUnit.SECONDS)
        return true
    }

    private fun BufferedInputStream.readFullyOrPartial(buffer: ByteArray): Int {
        var offset = 0
        while (offset < buffer.size) {
            val read = read(buffer, offset, buffer.size - offset)
            if (read < 0) break
            offset += read
        }
        return offset
    }

    private fun ByteArray.paddedPcmChunk(): ByteArray {
        val padded = ByteArray(PCM_20_MS_BYTES)
        copyInto(padded)
        return padded
    }

    private companion object {
        const val PCM_20_MS_BYTES = 3840
    }
}
