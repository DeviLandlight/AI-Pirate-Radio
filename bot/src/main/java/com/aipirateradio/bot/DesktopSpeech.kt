package com.aipirateradio.bot

import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.io.path.absolutePathString
import kotlin.io.path.writeText

class DesktopSpeechSynthesizer(
    private val outputDirectory: Path,
    private val voiceName: String? = null,
    private val rate: Int = 1,
    private val volume: Int = 100
) {
    fun speakToFile(text: String): Path? {
        if (text.isBlank()) return null
        Files.createDirectories(outputDirectory)
        val output = outputDirectory.resolve("dj-${UUID.randomUUID()}.wav")
        val textFile = outputDirectory.resolve("dj-${UUID.randomUUID()}.txt")
        textFile.writeText(text.normalizeForSpeech())
        val escapedPath = output.absolutePathString().replace("'", "''")
        val escapedTextPath = textFile.absolutePathString().replace("'", "''")
        val selectVoice = voiceName
            ?.takeIf { it.isNotBlank() }
            ?.replace("'", "''")
            ?.let { "${'$'}synth.SelectVoice('$it');" }
            .orEmpty()
        val script = """
            Add-Type -AssemblyName System.Speech;
            ${'$'}text = Get-Content -Raw -LiteralPath '$escapedTextPath';
            ${'$'}synth = New-Object System.Speech.Synthesis.SpeechSynthesizer;
            $selectVoice
            ${'$'}synth.Rate = $rate;
            ${'$'}synth.Volume = $volume;
            ${'$'}synth.SetOutputToWaveFile('$escapedPath');
            ${'$'}synth.Speak(${'$'}text);
            ${'$'}synth.Dispose();
        """.trimIndent()
        return try {
            val process = ProcessBuilder(
                "powershell",
                "-NoProfile",
                "-ExecutionPolicy",
                "Bypass",
                "-Command",
                script
            ).redirectErrorStream(true).start()
            val finished = process.waitFor(30, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return null
            }
            output.takeIf { process.exitValue() == 0 && Files.isRegularFile(it) }
        } finally {
            runCatching { Files.deleteIfExists(textFile) }
        }
    }

    companion object {
        fun listVoiceNames(): List<String> {
            val script = """
                Add-Type -AssemblyName System.Speech;
                ${'$'}synth = New-Object System.Speech.Synthesis.SpeechSynthesizer;
                ${'$'}synth.GetInstalledVoices() | ForEach-Object { ${'$'}_.VoiceInfo.Name };
                ${'$'}synth.Dispose();
            """.trimIndent()
            return runCatching {
                val process = ProcessBuilder(
                    "powershell",
                    "-NoProfile",
                    "-ExecutionPolicy",
                    "Bypass",
                    "-Command",
                    script
                ).redirectErrorStream(true).start()
                val output = process.inputStream.bufferedReader().use { it.readText() }
                process.waitFor(10, TimeUnit.SECONDS)
                output.lines().map { it.trim() }.filter { it.isNotBlank() }
            }.getOrDefault(emptyList())
        }
    }
}
