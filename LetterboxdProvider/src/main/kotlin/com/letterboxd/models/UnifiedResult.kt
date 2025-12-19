package com.letterboxd.models

import com.lagradost.cloudstream3.TvType
import kotlinx.serialization.Serializable

@Serializable
data class UnifiedSearchResult(
    val letterboxdFilm: LetterboxdFilm,
    val providerMatches: List<ProviderMatch> = emptyList()
)

@Serializable
data class ProviderMatch(
    val providerName: String,
    val providerIcon: String? = null,
    val matchUrl: String,
    val matchTitle: String,
    val matchType: TvType,
    val confidence: Float, // 0.0 to 1.0
    val quality: String? = null,
    val language: String? = null,
    val isDirectLink: Boolean = false,
    val requiresExtractor: Boolean = true
)

@Serializable
data class AggregatedLinks(
    val film: LetterboxdFilm,
    val availableProviders: Map<String, List<ProviderLink>> = emptyMap()
)

@Serializable
data class ProviderLink(
    val providerName: String,
    val url: String,
    val title: String,
    val quality: String? = null,
    val language: String? = null,
    val type: String, // "stream", "download", "external"
    val requiresExtractor: Boolean = true,
    val estimatedAvailability: AvailabilityStatus = AvailabilityStatus.UNKNOWN
)

enum class AvailabilityStatus {
    AVAILABLE,
    GEO_BLOCKED,
    REQUIRES_LOGIN,
    DEAD_LINK,
    UNKNOWN
}