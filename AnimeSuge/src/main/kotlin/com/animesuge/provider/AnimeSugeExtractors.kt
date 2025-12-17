package com.animesuge.provider

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper

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
            // Extract video ID from URL
            // URL format: https://megaplay.buzz/stream/s-4/130592?autostart=true
            val path = url.removePrefix("$mainUrl/stream/")
            val videoId = path.substringAfter("/").substringBefore("?")
            
            val apiUrl = "$mainUrl/stream/getSources?id=$videoId"
            
            val headers = mapOf(
                "Accept" to "*/*",
                "X-Requested-With" to "XMLHttpRequest",
                "Referer" to url
            )
            
            Log.d("AnimeSuge", "Fetching from API: $apiUrl")
            
            val response = try {
                app.get(apiUrl, headers = headers).parsedSafe<MegaPlayResponse>()
            } catch (e: Exception) {
                Log.e("AnimeSuge", "API call failed: ${e.message}")
                null
            }
            
            if (response?.sources?.file != null) {
                val m3u8Url = response.sources.file
                Log.d("AnimeSuge", "Got M3U8 URL: $m3u8Url")
                
                // Get subtitles
                response.tracks?.forEach { track ->
                    if ((track.kind == "captions" || track.kind == "subtitles") && track.file != null) {
                        subtitleCallback(newSubtitleFile(track.label ?: "Unknown", track.file))
                    }
                }
                
                // Generate M3U8 links
                M3u8Helper.generateM3u8(
                    name,
                    m3u8Url,
                    mainUrl,
                    headers = mapOf(
                        "Referer" to mainUrl,
                        "Origin" to mainUrl
                    )
                ).forEach(callback)
            } else {
                Log.e("AnimeSuge", "No sources found in API response")
                throw Exception("No video sources found")
            }
        } catch (e: Exception) {
            Log.e("AnimeSuge", "Extractor failed: ${e.message}")
            // Fallback: direct extraction
            loadExtractor(url, subtitleCallback, callback)
        }
    }
    
    data class MegaPlayResponse(
        @JsonProperty("sources") val sources: Sources? = null,
        @JsonProperty("tracks") val tracks: List<Track>? = null
    )
    
    data class Sources(
        @JsonProperty("file") val file: String? = null
    )
    
    data class Track(
        @JsonProperty("file") val file: String? = null,
        @JsonProperty("label") val label: String? = null,
        @JsonProperty("kind") val kind: String? = null
    )
}

// Additional extractor for other video hosts that AnimeSuge might use
class AnimeSugeStreamWish : ExtractorApi() {
    override val name = "AnimeSuge StreamWish"
    override val mainUrl = "https://streamwish.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        loadExtractor(url, subtitleCallback, callback)
    }
}

class AnimeSugeFileMoon : ExtractorApi() {
    override val name = "AnimeSuge FileMoon"
    override val mainUrl = "https://filemoon.sx"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        loadExtractor(url, subtitleCallback, callback)
    }
}
