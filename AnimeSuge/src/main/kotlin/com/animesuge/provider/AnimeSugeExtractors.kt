package com.animesuge.provider

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.loadExtractor

class AnimeSugeMegaPlay : ExtractorApi() {
    override val name = "AnimeSuge MegaPlay"
    override val mainUrl = "https://megaplay.buzz"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            // Extract the ID from the URL
            val path = url.removePrefix("$mainUrl/stream/")
            val parts = path.split("/")
            
            var videoId: String? = null
            if (parts.isNotEmpty()) {
                // The ID could be in different positions
                when {
                    parts.size >= 2 -> {
                        val possibleId = parts[1].substringBefore("?")
                        videoId = if (possibleId == "sub" || possibleId == "dub") {
                            parts[0]
                        } else {
                            possibleId
                        }
                    }
                    parts.size == 1 -> {
                        videoId = parts[0].substringBefore("?")
                    }
                }
            }
            
            if (videoId != null) {
                // Try the API first
                tryApiMethod(videoId, url, subtitleCallback, callback)
            } else {
                // Fallback to direct extraction
                loadExtractor(url, subtitleCallback, callback)
            }
        } catch (e: Exception) {
            // Fallback to generic extractor
            loadExtractor(url, subtitleCallback, callback)
        }
    }
    
    private suspend fun tryApiMethod(
        videoId: String,
        originalUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val apiUrl = "$mainUrl/stream/getSources?id=$videoId"
            val headers = mapOf(
                "Accept" to "*/*",
                "X-Requested-With" to "XMLHttpRequest",
                "Referer" to originalUrl,
                "Origin" to mainUrl
            )
            
            val response = app.get(apiUrl, headers = headers).parsedSafe<MegaPlayResponse>()
            
            if (response?.sources?.file != null) {
                val m3u8Url = response.sources.file
                
                // Get subtitles if available
                response.tracks?.forEach { track ->
                    if ((track.kind == "captions" || track.kind == "subtitles") && track.file != null) {
                        subtitleCallback(newSubtitleFile(
                            track.label ?: if (track.kind == "captions") "English" else "Unknown",
                            track.file
                        ))
                    }
                }
                
                // Generate M3U8 links with quality detection
                M3u8Helper.generateM3u8(
                    name,
                    m3u8Url,
                    mainUrl,
                    headers = mapOf(
                        "Referer" to mainUrl,
                        "Origin" to mainUrl,
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                    )
                ).forEach(callback)
            } else {
                // If API doesn't work, try direct extraction
                loadExtractor(originalUrl, subtitleCallback, callback)
            }
        } catch (e: Exception) {
            loadExtractor(originalUrl, subtitleCallback, callback)
        }
    }
    
    data class MegaPlayResponse(
        @JsonProperty("sources") val sources: Sources? = null,
        @JsonProperty("tracks") val tracks: List<Track>? = null,
        @JsonProperty("server") val server: Int? = null,
        @JsonProperty("intro") val intro: Intro? = null,
        @JsonProperty("outro") val outro: Outro? = null
    )
    
    data class Sources(
        @JsonProperty("file") val file: String? = null,
        @JsonProperty("type") val type: String? = null
    )
    
    data class Track(
        @JsonProperty("file") val file: String? = null,
        @JsonProperty("label") val label: String? = null,
        @JsonProperty("kind") val kind: String? = null,
        @JsonProperty("default") val default: Boolean? = null
    )
    
    data class Intro(
        @JsonProperty("start") val start: Int? = null,
        @JsonProperty("end") val end: Int? = null
    )
    
    data class Outro(
        @JsonProperty("start") val start: Int? = null,
        @JsonProperty("end") val end: Int? = null
    )
}
