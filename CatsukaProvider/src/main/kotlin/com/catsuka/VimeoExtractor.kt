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
            // First, try to get the player page HTML
            val playerUrl = "https://player.vimeo.com/video/$videoId"
            val response = app.get(playerUrl, headers = mapOf(
                "Referer" to (referer ?: "https://www.catsuka.com/"),
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
            ))
            
            if (response.isSuccessful) {
                val html = response.text
                
                // Extract the playerConfig JSON from the script tag
                val regex = Regex("""window\.playerConfig\s*=\s*(\{.*?\})\s*;""", RegexOption.DOT_MATCHES_ALL)
                val match = regex.find(html)
                
                if (match != null) {
                    val jsonText = match.groupValues[1]
                    val json = tryParseJson<VimeoPlayerConfig>(jsonText)
                    
                    json?.request?.files?.let { files ->
                        // First priority: HLS stream (usually works best)
                        files.hls?.cdns?.forEach { (cdnName, cdn) ->
                            cdn.url?.let { hlsUrl ->
                                // Generate M3U8 links with proper headers
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
                        
                        // Second priority: DASH stream
                        files.dash?.cdns?.forEach { (cdnName, cdn) ->
                            cdn.url?.let { dashUrl ->
                                // Get quality from streams array
                                val quality = files.dash?.streams?.maxByOrNull { 
                                    when (it.quality) {
                                        "1080p" -> Qualities.P1080.value
                                        "720p" -> Qualities.P720.value
                                        "540p" -> Qualities.P480.value  // No P540, use 480
                                        "360p" -> Qualities.P360.value
                                        "240p" -> Qualities.P240.value
                                        else -> Qualities.Unknown.value
                                    }
                                }?.quality ?: "Unknown"
                                
                                callback.invoke(
                                    newExtractorLink(
                                        source = name,
                                        name = "Vimeo DASH - $quality",
                                        url = dashUrl,
                                        referer = "https://vimeo.com/"
                                    ) {
                                        this.quality = when (quality) {
                                            "1080p" -> Qualities.P1080.value
                                            "720p" -> Qualities.P720.value
                                            "540p" -> Qualities.P480.value
                                            "360p" -> Qualities.P360.value
                                            "240p" -> Qualities.P240.value
                                            else -> Qualities.Unknown.value
                                        }
                                    }
                                )
                            }
                        }
                    }
                    return
                }
            }
        } catch (e: Exception) {
            // Try fallback method
        }
        
        // Fallback: Try direct config API
        try {
            val configUrl = "https://player.vimeo.com/video/$videoId/config"
            val configResponse = app.get(configUrl, headers = mapOf(
                "Referer" to (referer ?: "https://www.catsuka.com/"),
                "Origin" to "https://player.vimeo.com",
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
            ))
            
            if (configResponse.isSuccessful) {
                val json = tryParseJson<Map<String, Any>>(configResponse.text)
                
                // Extract HLS URL
                val hlsUrl = json
                    ?.get("request") as? Map<String, Any>
                    ?.get("files") as? Map<String, Any>
                    ?.get("hls") as? Map<String, Any>
                    ?.get("url") as? String
                
                if (hlsUrl != null) {
                    M3u8Helper.generateM3u8(
                        source = name,
                        streamUrl = hlsUrl,
                        referer = "https://vimeo.com/"
                    ).forEach(callback)
                    return
                }
            }
        } catch (e: Exception) {
            // Continue to final fallback
        }
        
        // Final fallback: Embed URL
        callback.invoke(
            newExtractorLink(
                source = name,
                name = "Vimeo Embed",
                url = "https://player.vimeo.com/video/$videoId",
                referer = "https://vimeo.com/"
            )
        )
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
        @JsonProperty("url") val url: String? = null
    )

    data class VimeoStream(
        @JsonProperty("quality") val quality: String? = null
    )
}
