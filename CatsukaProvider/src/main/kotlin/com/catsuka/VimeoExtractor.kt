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
        try {
            val videoId = url.substringAfterLast("/").substringBefore("?")
            
            // Get the player page to extract the config
            val playerUrl = "https://player.vimeo.com/video/$videoId"
            val response = app.get(playerUrl, headers = mapOf(
                "Referer" to (referer ?: "https://www.catsuka.com/"),
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
            ))
            
            if (response.isSuccessful) {
                val html = response.text
                
                // Extract the window.playerConfig JSON
                val regex = Regex("""window\.playerConfig\s*=\s*(\{.*?\})\s*;""", RegexOption.DOT_MATCHES_ALL)
                val match = regex.find(html)
                
                if (match != null) {
                    val jsonText = match.groupValues[1]
                    val json = tryParseJson<VimeoPlayerConfig>(jsonText)
                    
                    json?.request?.files?.let { files ->
                        // Get HLS stream (most reliable)
                        files.hls?.cdns?.values?.firstOrNull()?.url?.let { hlsUrl ->
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
                            return
                        }
                        
                        // Fallback to DASH stream
                        files.dash?.cdns?.values?.firstOrNull()?.url?.let { dashUrl ->
                            callback.invoke(
                                newExtractorLink(
                                    source = name,
                                    name = "Vimeo DASH",
                                    url = dashUrl,
                                    referer = "https://vimeo.com/"
                                ) {
                                    this.quality = Qualities.P1080.value
                                }
                            )
                            return
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        // Ultimate fallback: Use the embed URL directly
        val videoId = url.substringAfterLast("/").substringBefore("?")
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
        @JsonProperty("cdns") val cdns: Map<String, VimeoCdn>? = null
    )

    data class VimeoHls(
        @JsonProperty("cdns") val cdns: Map<String, VimeoCdn>? = null
    )

    data class VimeoCdn(
        @JsonProperty("url") val url: String? = null
    )
}
