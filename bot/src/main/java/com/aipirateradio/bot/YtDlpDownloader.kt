package com.aipirateradio.bot

import com.aipirateradio.app.station.Song
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class YtDlpDownloader(
    private val executable: String,
    private val outputDirectory: Path
) {
    private var resolvedExecutable: String = executable

    fun isAvailable(): Boolean {
        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        val candidates = if (isWindows && !executable.endsWith(".exe", ignoreCase = true) && !executable.contains("\\") && !executable.contains("/")) {
            listOf("$executable.exe", executable)
        } else {
            listOf(executable)
        }
        for (candidate in candidates) {
            val success = runCatching {
                val process = ProcessBuilder(candidate, "--version").start()
                val exited = process.waitFor(30, TimeUnit.SECONDS)
                if (!exited) {
                    System.err.println("yt-dlp check timed out for '$candidate'")
                    process.destroyForcibly()
                    false
                } else {
                    val exitCode = process.exitValue()
                    if (exitCode != 0) {
                        val errorOutput = process.errorStream.bufferedReader().readText()
                        System.err.println("yt-dlp check failed for '$candidate' with exit code $exitCode. Error: $errorOutput")
                        false
                    } else {
                        true
                    }
                }
            }.onFailure {
                System.err.println("yt-dlp check failed for '$candidate' with exception: ${it.message}")
                it.printStackTrace()
            }.getOrDefault(false)

            if (success) {
                resolvedExecutable = candidate
                return true
            }
        }
        return false
    }

    fun download(song: Song): DownloadResult {
        Files.createDirectories(outputDirectory)
        val outputTemplate = outputDirectory.resolve("${safeFileName(song.artist)} - ${safeFileName(song.title)}.%(ext)s").toString()
        val query = "ytsearch1:${song.artist} ${song.title} audio"
        val process = ProcessBuilder(
            resolvedExecutable,
            "--no-playlist",
            "--extract-audio",
            "--audio-format",
            "mp3",
            "--audio-quality",
            "0",
            "--embed-thumbnail",
            "--convert-thumbnails",
            "jpg",
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
