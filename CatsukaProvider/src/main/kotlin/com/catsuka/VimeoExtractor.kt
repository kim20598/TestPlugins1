package com.catsuka.provider

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.fasterxml.jackson.annotation.JsonProperty

class VimeoExtractor : ExtractorApi() {
    override val name = "CatsukaVimeo"
    override val mainUrl = "https://vimeo.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val videoId = url.substringAfterLast("/").substringBefore("?")
        
        try {
            val configUrl = "https://player.vimeo.com/video/$videoId/config"
            val response = app.get(configUrl, headers = mapOf(
                "Referer" to "https://www.catsuka.com/"
            ))
            
            if (response.isSuccessful) {
                val json = tryParseJson<VimeoConfig>(response.text)
                val files = json?.request?.files
                
                files?.progressive?.forEach { video ->
                    val quality = video.quality ?: "360p"
                    val videoUrl = video.url
                    
                    if (videoUrl != null) {
                        callback.invoke(
                            newExtractorLink(
                                source = name,
                                name = "Vimeo - $quality",
                                url = videoUrl
                            ) {
                                this.referer = "https://vimeo.com/"
                                this.quality = getQualityFromName(quality)
                            }
                        )
                    }
                }
                
                // Try HLS stream
                files?.hls?.url?.let { hlsUrl ->
                    M3u8Helper.generateM3u8(
                        name,
                        hlsUrl,
                        "https://vimeo.com/",
                        headers = mapOf("Referer" to "https://www.catsuka.com/")
                    ).forEach(callback)
                }
            }
        } catch (e: Exception) {
            // Fallback to direct embed
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
    }

    // Data classes for Vimeo JSON parsing
    data class VimeoConfig(
        @JsonProperty("request") val request: VimeoRequest?
    )

    data class VimeoRequest(
        @JsonProperty("files") val files: VimeoFiles?
    )

    data class VimeoFiles(
        @JsonProperty("progressive") val progressive: List<ProgressiveVideo>?,
        @JsonProperty("hls") val hls: HlsVideo?
    )

    data class ProgressiveVideo(
        @JsonProperty("url") val url: String?,
        @JsonProperty("quality") val quality: String?
    )

    data class HlsVideo(
        @JsonProperty("url") val url: String?
    )
}
