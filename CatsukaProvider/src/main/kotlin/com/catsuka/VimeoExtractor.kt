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
            // Get player page with proper headers
            val playerUrl = "https://player.vimeo.com/video/$videoId"
            val response = app.get(playerUrl, headers = mapOf(
                "Referer" to (referer ?: "https://www.catsuka.com/"),
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
                "Accept-Language" to "en-US,en;q=0.9",
                "Accept-Encoding" to "gzip, deflate, br",
                "Connection" to "keep-alive",
                "Upgrade-Insecure-Requests" to "1",
                "Sec-Fetch-Dest" to "document",
                "Sec-Fetch-Mode" to "navigate",
                "Sec-Fetch-Site" to "cross-site"
            ))
            
            if (response.isSuccessful) {
                val html = response.text
                
                // Extract window.playerConfig JSON
                val regex = Regex("""window\.playerConfig\s*=\s*(\{.*?\})\s*;""", RegexOption.DOT_MATCHES_ALL)
                val match = regex.find(html)
                
                if (match != null) {
                    val jsonText = match.groupValues[1]
                    val json = tryParseJson<VimeoPlayerConfig>(jsonText)
                    
                    // Get quality information
                    val qualityMap = mutableMapOf<String, String>()
                    json?.request?.files?.dash?.streams?.forEach { stream ->
                        stream.id?.let { id ->
                            stream.quality?.let { quality ->
                                qualityMap[id] = quality
                            }
                        }
                    }
                    
                    // Send HLS streams
                    json?.request?.files?.hls?.cdns?.forEach { (cdnName, cdn) ->
                        cdn.url?.let { hlsUrl ->
                            M3u8Helper.generateM3u8(
                                source = name,
                                streamUrl = hlsUrl,
                                referer = "https://vimeo.com/",
                                headers = mapOf(
                                    "Referer" to "https://vimeo.com/",
                                    "Origin" to "https://player.vimeo.com",
                                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                                )
                            ).forEach(callback)
                        }
                    }
                    
                    // Send DASH streams
                    json?.request?.files?.dash?.cdns?.forEach { (cdnName, cdn) ->
                        cdn.url?.let { dashUrl ->
                            val quality = qualityMap.values.firstOrNull() ?: "1080p"
                            newExtractorLink(
                                source = name,
                                name = "Vimeo DASH - $quality",
                                url = dashUrl
                            ) {
                                this.referer = "https://vimeo.com/"
                                this.quality = getQuality(quality)
                            }?.let { callback(it) }
                        }
                    }
                    
                    return // Success
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        // Fallback
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
            quality.contains("540", true) -> 540
            quality.contains("480", true) -> Qualities.P480.value
            quality.contains("360", true) -> Qualities.P360.value
            quality.contains("240", true) -> Qualities.P240.value
            else -> Qualities.Unknown.value
        }
    }

    data class VimeoPlayerConfig(
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
        @JsonProperty("cdns") val cdns: Map<String, VimeoCdn>? = null,
        @JsonProperty("streams") val streams: List<VimeoStream>? = null
    )

    data class VimeoHls(
        @JsonProperty("cdns") val cdns: Map<String, VimeoCdn>? = null
    )

    data class VimeoCdn(
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("avc_url") val avcUrl: String? = null
    )

    data class VimeoStream(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("quality") val quality: String? = null
    )
}
