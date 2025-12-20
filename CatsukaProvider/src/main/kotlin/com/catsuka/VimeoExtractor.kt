package com.catsuka.provider

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.fasterxml.jackson.annotation.JsonProperty
import org.jsoup.Jsoup

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
            // Try to get from Vimeo config API
            val configUrl = "https://player.vimeo.com/video/$videoId/config"
            val response = app.get(configUrl, referer = referer ?: "https://www.catsuka.com/")
            
            if (response.isSuccessful) {
                val json = response.parsedSafe<VimeoConfig>()
                val files = json?.request?.files
                
                // First try progressive MP4s
                files?.progressive?.forEach { video ->
                    val quality = video.quality ?: "360p"
                    val videoUrl = video.url
                    
                    if (videoUrl != null && videoUrl.isNotBlank()) {
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
                
                // Then try HLS (usually higher quality)
                files?.hls?.url?.let { hlsUrl ->
                    if (hlsUrl.isNotBlank()) {
                        M3u8Helper.generateM3u8(
                            source = name,
                            streamUrl = hlsUrl,
                            referer = referer ?: "https://vimeo.com/"
                        ).forEach(callback)
                    }
                }
                
                // If we got any links, return
                return
            }
        } catch (e: Exception) {
            // Try alternative method
        }
        
        // Fallback: Try to get from the Vimeo page directly
        try {
            val pageUrl = "https://vimeo.com/$videoId"
            val page = app.get(pageUrl, referer = referer ?: "https://www.catsuka.com/")
            val document = Jsoup.parse(page.text)
            
            // Look for video URLs in the page
            val scripts = document.select("script")
            for (script in scripts) {
                val scriptText = script.html()
                
                // Look for config JSON
                val configPattern = Regex(""""progressive":\s*\[(.*?)\]\s*,\s*"hls":""", RegexOption.DOT_MATCHES_ALL)
                val match = configPattern.find(scriptText)
                
                if (match != null) {
                    // Try to extract progressive URLs
                    val progressivePattern = Regex("""\{"url":"(https:[^"]+)","quality":"([^"]+)"\}""")
                    progressivePattern.findAll(scriptText).forEach { progressiveMatch ->
                        val videoUrl = progressiveMatch.groupValues[1].replace("\\/", "/")
                        val quality = progressiveMatch.groupValues[2]
                        
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
                    
                    // Try to extract HLS URL
                    val hlsPattern = Regex(""""hls":{"url":"(https:[^"]+)""")
                    val hlsMatch = hlsPattern.find(scriptText)
                    if (hlsMatch != null) {
                        val hlsUrl = hlsMatch.groupValues[1].replace("\\/", "/")
                        M3u8Helper.generateM3u8(
                            source = name,
                            streamUrl = hlsUrl,
                            referer = referer ?: "https://vimeo.com/"
                        ).forEach(callback)
                    }
                    
                    return
                }
            }
        } catch (e: Exception) {
            // Fallback to player URL
        }
        
        // Ultimate fallback: Return the player URL and let CloudStream handle it
        callback.invoke(
            newExtractorLink(
                source = name,
                name = "Vimeo Player",
                url = "https://player.vimeo.com/video/$videoId"
            ) {
                this.referer = "https://vimeo.com/"
            }
        )
    }
    
    private fun getQualityFromName(quality: String): Int {
        return when {
            quality.contains("4k", true) -> Qualities.P2160.value
            quality.contains("2k", true) -> Qualities.P1440.value
            quality.contains("1080", true) -> Qualities.P1080.value
            quality.contains("720", true) -> Qualities.P720.value
            quality.contains("480", true) -> Qualities.P480.value
            quality.contains("360", true) -> Qualities.P360.value
            quality.contains("240", true) -> Qualities.P240.value
            else -> Qualities.Unknown.value
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
