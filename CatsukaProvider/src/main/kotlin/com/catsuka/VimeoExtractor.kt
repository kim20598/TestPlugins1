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
            // Get the player page HTML
            val playerUrl = "https://player.vimeo.com/video/$videoId"
            val response = app.get(playerUrl, headers = mapOf(
                "Referer" to (referer ?: "https://www.catsuka.com/"),
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
            ))
            
            if (response.isSuccessful) {
                val html = response.text
                
                // Look for window.playerConfig JSON in the HTML
                val regex = Regex("""window\.playerConfig\s*=\s*(\{.*?\})\s*;""", RegexOption.DOT_MATCHES_ALL)
                val match = regex.find(html)
                
                if (match != null) {
                    val jsonText = match.groupValues[1]
                    val json = tryParseJson<VimeoConfig>(jsonText)
                    
                    // Extract DASH stream URLs
                    json?.request?.files?.dash?.cdns?.forEach { (cdnName, cdn) ->
                        cdn.url?.let { dashUrl ->
                            // Send DASH stream link
                            newExtractorLink(
                                source = name,
                                name = "Vimeo DASH",
                                url = dashUrl
                            ) {
                                this.referer = "https://vimeo.com/"
                                this.quality = Qualities.P1080.value
                            }?.let { callback(it) }
                        }
                    }
                    
                    // Extract HLS stream URLs
                    json?.request?.files?.hls?.cdns?.forEach { (cdnName, cdn) ->
                        cdn.url?.let { hlsUrl ->
                            // Send HLS stream link
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
                    
                    return // Success, we found video URLs
                }
            }
        } catch (e: Exception) {
            // If extraction fails, continue to fallback
        }
        
        // Fallback: Send embed URL
        newExtractorLink(
            source = name,
            name = "Vimeo Embed",
            url = "https://player.vimeo.com/video/$videoId"
        ) {
            this.referer = "https://vimeo.com/"
        }?.let { callback(it) }
    }
    
    private fun getQuality(quality: String): Int {
        return when {
            quality.contains("4k", true) -> Qualities.P2160.value
            quality.contains("1440", true) -> Qualities.P1440.value
            quality.contains("1080", true) -> Qualities.P1080.value
            quality.contains("720", true) -> Qualities.P720.value
            quality.contains("540", true) -> 540  // Custom value for 540p
            quality.contains("480", true) -> Qualities.P480.value
            quality.contains("360", true) -> Qualities.P360.value
            quality.contains("240", true) -> Qualities.P240.value
            else -> Qualities.Unknown.value
        }
    }

    // Data classes based on your HTML structure
    data class VimeoConfig(
        @JsonProperty("request") val request: VimeoRequest? = null
    )

    data class VimeoRequest(
        @JsonProperty("files") val files: VimeoFiles? = null
    )

    data class VimeoFiles(
        @JsonProperty("dash") val dash: VimeoDash? = null,
        @JsonProperty("hls") val hls: VimeoHls? = null
    )

    data class VimeoDash(
        @JsonProperty("cdns") val cdns: Map<String, VimeoCdn>? = null
    )

    data class VimeoHls(
        @JsonProperty("cdns") val cdns: Map<String, VimeoCdn>? = null
    )

    data class VimeoCdn(
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("avc_url") val avcUrl: String? = null,
        @JsonProperty("origin") val origin: String? = null
    )
}
