package com.aipirateradio.bot

import com.aipirateradio.app.station.Song
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class YtDlpDownloader(
    private val executable: String,
    private val outputDirectory: Path
) {
    fun isAvailable(): Boolean {
        return runCatching {
            ProcessBuilder(executable, "--version").start().waitFor(5, TimeUnit.SECONDS)
        }.getOrDefault(false)
    }

    fun download(song: Song): DownloadResult {
        Files.createDirectories(outputDirectory)
        val outputTemplate = outputDirectory.resolve("${safeFileName(song.artist)} - ${safeFileName(song.title)}.%(ext)s").toString()
        val query = "ytsearch1:${song.artist} ${song.title} audio"
        val process = ProcessBuilder(
            executable,
            "--no-playlist",
            "--extract-audio",
            "--audio-format",
            "mp3",
            "--audio-quality",
            "0",
            "--output",
            outputTemplate,
            query
        ).redirectErrorStream(true).start()

        val output = process.inputStream.bufferedReader().use { it.readText() }
        val finished = process.waitFor(10, TimeUnit.MINUTES)
        if (!finished) {
            process.destroyForcibly()
            return DownloadResult(false, "timed out")
        }
        return if (process.exitValue() == 0) {
            DownloadResult(true, "downloaded")
        } else {
            DownloadResult(false, output.lines().lastOrNull { it.isNotBlank() } ?: "yt-dlp failed")
        }
    }

    private fun safeFileName(value: String): String {
        return value
            .replace(Regex("[<>:\"/\\\\|?*]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(120)
            .ifBlank { "Unknown" }
    }
}

data class DownloadResult(
    val success: Boolean,
    val message: String
)
