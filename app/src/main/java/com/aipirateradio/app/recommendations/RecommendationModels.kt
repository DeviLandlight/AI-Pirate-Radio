package com.aipirateradio.app.recommendations

import com.aipirateradio.app.station.Song

data class FavoriteArtistSeed(val name: String)

data class RecommendationRequest(
    val favoriteArtists: List<FavoriteArtistSeed>,
    val includeObscureTracks: Boolean = true,
    val includeBSides: Boolean = true,
    val maxArtistsPerSeed: Int = 8,
    val maxTracksPerArtist: Int = 8
)

data class RecommendationPool(
    val songs: List<Song>,
    val sourceNotes: List<String>
)

interface MusicRecommender {
    suspend fun buildPool(request: RecommendationRequest): RecommendationPool
}

class StaticSeedRecommender(private val fallbackSongs: List<Song>) : MusicRecommender {
    override suspend fun buildPool(request: RecommendationRequest): RecommendationPool {
        val seeds = request.favoriteArtists.joinToString { it.name }
        return RecommendationPool(fallbackSongs, listOf("Sample catalog for seeds: $seeds"))
    }
}
