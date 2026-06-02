package com.aipirateradio.app.local

import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import com.aipirateradio.app.station.Song
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.ffmpeg.FFmpeg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class AndroidYtDlpDownloader(
    context: Context,
    private val reportStatus: (String) -> Unit = {}
) {
    private val appContext = context.applicationContext
    private val initialized = AtomicBoolean(false)
    private val updaterChecked = AtomicBoolean(false)

    suspend fun downloadMissing(songs: List<Song>, maxDownloads: Int = DEFAULT_MAX_DOWNLOADS): DownloadSummary = withContext(Dispatchers.IO) {
        val missingSongs = songs.filterNot { it.audioUri.startsWith("content://") || it.audioUri.startsWith("file://") }.take(maxDownloads)
        if (missingSongs.isEmpty()) return@withContext DownloadSummary(0, emptyList())
        ensureInitialized()
        val outputDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "AI Pirate Radio")
        outputDir.mkdirs()

        var downloaded = 0
        val failures = mutableListOf<String>()
        val downloadedAudioUris = mutableMapOf<String, String>()
        missingSongs.forEachIndexed { index, song ->
            reportStatus("Downloading ${index + 1}/${missingSongs.size}: ${song.artist} - ${song.title}.")
            val before = outputDir.listFiles()?.toSet().orEmpty()
            val outputTemplate = File(outputDir, "${song.artist.safeFilePart()} - ${song.title.safeFilePart()}.%(ext)s")
            val request = YoutubeDLRequest("ytsearch1:${song.artist} ${song.title} audio")
            request.addOption("--no-playlist")
            request.addOption("--extract-audio")
            request.addOption("--audio-format", "mp3")
            request.addOption("--audio-quality", "0")
            request.addOption("--embed-thumbnail")
            request.addOption("--convert-thumbnails", "jpg")
            request.addOption("--output", outputTemplate.absolutePath)
            val result = runCatching {
                YoutubeDL.getInstance().execute(request) { progress, etaInSeconds, _ ->
                    if (progress > 0f) {
                        reportStatus("Downloading ${song.title}: ${progress.toInt()}% (${etaInSeconds}s).")
                    }
                }
            }
            if (result.isSuccess) {
                downloaded += 1
                val after = outputDir.listFiles()?.toSet().orEmpty()
                val expectedFile = File(outputDir, "${song.artist.safeFilePart()} - ${song.title.safeFilePart()}.mp3")
                val newFiles = (after - before).filter { it.isFile }
                val downloadedFile = expectedFile.takeIf { it.isFile } ?: newFiles.maxByOrNull { it.lastModified() }
                if (downloadedFile != null) {
                    downloadedAudioUris[song.id] = Uri.fromFile(downloadedFile).toString()
                }
                if (newFiles.isNotEmpty()) {
                    MediaScannerConnection.scanFile(appContext, newFiles.map { it.absolutePath }.toTypedArray(), null, null)
                }
            } else {
                val songLabel = "${song.artist} - ${song.title}"
                failures += songLabel
                val message = result.exceptionOrNull().toDownloadErrorMessage()
                reportStatus("Download failed for ${song.artist} - ${song.title}: $message.")
            }
        }
        DownloadSummary(downloaded, failures, downloadedAudioUris)
    }

    private fun ensureInitialized() {
        if (initialized.get()) return
        synchronized(initialized) {
            if (initialized.get()) return
            YoutubeDL.getInstance().init(appContext)
            FFmpeg.getInstance().init(appContext)
            initialized.set(true)
        }
        updateYoutubeDlIfNeeded()
    }

    private fun updateYoutubeDlIfNeeded() {
        if (updaterChecked.get()) return
        synchronized(updaterChecked) {
            if (updaterChecked.get()) return
            reportStatus("Checking downloader update.")
            val updateResult = runCatching {
                YoutubeDL.getInstance().updateYoutubeDL(appContext, YoutubeDL.UpdateChannel.STABLE)
            }
            updateResult.onSuccess { status ->
                val message = when (status) {
                    YoutubeDL.UpdateStatus.DONE -> "Downloader updated. Starting downloads."
                    YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE -> "Downloader is up to date. Starting downloads."
                    else -> "Downloader checked. Starting downloads."
                }
                reportStatus(message)
            }.onFailure { error ->
                reportStatus("Downloader update failed; trying bundled downloader. ${error.message.orEmpty()}")
            }
            updaterChecked.set(true)
        }
    }

    private companion object {
        const val DEFAULT_MAX_DOWNLOADS = 12
    }
}

data class DownloadSummary(
    val downloaded: Int,
    val failedSongs: List<String>,
    val downloadedAudioUris: Map<String, String> = emptyMap()
) {
    val failed: Int get() = failedSongs.size
}

private fun String.safeFilePart(): String {
    return replace(Regex("[<>:\"/\\\\|?*]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(90)
        .ifBlank { "Unknown" }
}

private fun Throwable?.toDownloadErrorMessage(): String {
    val raw = this?.message.orEmpty()
    return when {
        raw.contains("No address associated with hostname", ignoreCase = true) -> "network lookup failed; try again when the connection is steady"
        raw.contains("HTTP Error 403", ignoreCase = true) || raw.contains("Forbidden", ignoreCase = true) -> "YouTube refused this request; try again after the downloader updates"
        raw.contains("older than 90 days", ignoreCase = true) -> "downloader is outdated; try again after the updater finishes"
        raw.isNotBlank() -> raw.lineSequence().lastOrNull { it.contains("ERROR", ignoreCase = true) }?.take(180)
            ?: raw.lineSequence().firstOrNull { it.isNotBlank() }?.take(180)
            ?: this?.javaClass?.simpleName.orEmpty()
        else -> this?.javaClass?.simpleName.orEmpty()
    }.ifBlank { "unknown error" }
}
