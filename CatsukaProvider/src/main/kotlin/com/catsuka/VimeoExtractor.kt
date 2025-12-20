package com.catsuka.provider

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson

class VimeoExtractor : ExtractorApi() {
    override val name = "CatsukaVimeo"
    override val mainUrl = "https://vimeo.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val videoId = url.substringAfterLast("/").substringBefore("?")
        
        try {
            // Try Vimeo's player config API with proper headers
            val configUrl = "https://player.vimeo.com/video/$videoId/config"
            val response = app.get(
                configUrl,
                headers = mapOf(
                    "Referer" to (referer ?: "https://www.catsuka.com/"),
                    "Origin" to "https://player.vimeo.com",
                    "Accept" to "application/json",
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                )
            )
            
            if (response.isSuccessful) {
                val text = response.text
                // Parse JSON manually
                val json = tryParseJson<VimeoConfig>(text)
                
                json?.request?.files?.let { files ->
                    // Progressive videos (MP4)
                    files.progressive?.forEach { video ->
                        video.url?.let { videoUrl ->
                            val quality = video.quality ?: "unknown"
                            callback.invoke(
                                newExtractorLink(
                                    source = name,
                                    name = "Vimeo $quality",
                                    url = videoUrl
                                ) {
                                    this.referer = "https://vimeo.com/"
                                    this.quality = getQuality(quality)
                                }
                            )
                        }
                    }
                    
                    // HLS stream
                    files.hls?.url?.let { hlsUrl ->
                        M3u8Helper.generateM3u8(
                            source = name,
                            streamUrl = hlsUrl,
                            referer = "https://vimeo.com/",
                            headers = mapOf(
                                "Referer" to "https://vimeo.com/",
                                "Origin" to "https://player.vimeo.com"
                            )
                        ).forEach(callback)
                    }
                }
                return
            }
        } catch (e: Exception) {
            // Try alternative method
        }
        
        // Fallback: Use embed URL and let CloudStream handle it
        callback.invoke(
            newExtractorLink(
                source = name,
                name = "Vimeo Embed",
                url = "https://player.vimeo.com/video/$videoId"
            ) {
                this.referer = "https://vimeo.com/"
            }
        )
    }
    
    private fun getQuality(quality: String): Int {
        return when {
            quality.contains("4k", true) -> Qualities.P2160.value
            quality.contains("1440", true) -> Qualities.P1440.value
            quality.contains("1080", true) -> Qualities.P1080.value
            quality.contains("720", true) -> Qualities.P720.value
            // Removed P540 - doesn't exist
            quality.contains("480", true) -> Qualities.P480.value
            quality.contains("360", true) -> Qualities.P360.value
            quality.contains("240", true) -> Qualities.P240.value
            else -> Qualities.Unknown.value
        }
    }

    data class VimeoConfig(
        @JsonProperty("request") val request: VimeoRequest? = null
    )

    data class VimeoRequest(
        @JsonProperty("files") val files: VimeoFiles? = null
    )

    data class VimeoFiles(
        @JsonProperty("progressive") val progressive: List<ProgressiveVideo>? = null,
        @JsonProperty("hls") val hls: HlsVideo? = null
    )

    data class ProgressiveVideo(
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("quality") val quality: String? = null
    )

    data class HlsVideo(
        @JsonProperty("url") val url: String? = null
    )
}
