package com.aipirateradio.app.openai

interface TtsPlayer {
    suspend fun play(audioBytes: ByteArray?)
}

class SilentTtsPlayer : TtsPlayer {
    override suspend fun play(audioBytes: ByteArray?) = Unit
}
