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
        
        // Try multiple methods to extract Vimeo video
        // Method 1: Try player page HTML
        runCatching {
            extractFromPlayerPage(videoId, referer, callback)
        }.onSuccess { if (it) return }
        
        // Method 2: Try config API
        runCatching {
            extractFromConfigApi(videoId, referer, callback)
        }.onSuccess { if (it) return }
        
        // Method 3: Try oEmbed
        runCatching {
            extractFromOEmbed(videoId, referer, callback)
        }.onSuccess { if (it) return }
        
        // Fallback to embed URL
        fallbackToEmbed(videoId, callback)
    }
    
    // Method 1: Extract from player page HTML
    private suspend fun extractFromPlayerPage(videoId: String, referer: String?, callback: (ExtractorLink) -> Unit): Boolean {
        return try {
            val playerUrl = "https://player.vimeo.com/video/$videoId"
            val response = app.get(playerUrl, headers = mapOf(
                "Referer" to (referer ?: "https://www.catsuka.com/"),
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
            ))
            
            if (response.isSuccessful) {
                val html = response.text
                
                // Try to extract window.playerConfig JSON
                val regex = Regex("""window\.playerConfig\s*=\s*(\{.*?\})\s*;""", RegexOption.DOT_MATCHES_ALL)
                val match = regex.find(html)
                
                if (match != null) {
                    val jsonText = match.groupValues[1]
                    val json = tryParseJson<VimeoPlayerConfig>(jsonText)
                    
                    json?.request?.files?.let { files ->
                        // Extract HLS URLs from cdns
                        files.hls?.cdns?.forEach { (cdnName, cdn) ->
                            cdn.url?.let { hlsUrl ->
                                M3u8Helper.generateM3u8(
                                    source = name,
                                    streamUrl = hlsUrl,
                                    referer = "https://vimeo.com/",
                                    headers = mapOf(
                                        "Referer" to "https://vimeo.com/",
                                        "Origin" to "https://player.vimeo.com"
                                    )
                                ).forEach(callback)
                                return true
                            }
                        }
                        
                        // Extract DASH URLs
                        files.dash?.cdns?.forEach { (cdnName, cdn) ->
                            cdn.url?.let { dashUrl ->
                                newExtractorLink(
                                    source = name,
                                    name = "Vimeo DASH",
                                    url = dashUrl
                                ) {
                                    this.referer = "https://vimeo.com/"
                                    this.quality = Qualities.P1080.value
                                }?.let { callback(it) }
                                return true
                            }
                        }
                    }
                }
                
                // Try alternative pattern for video URLs
                val videoRegex = Regex("""(https?:[^"']+\.(?:m3u8|mp4)[^"']*)""")
                val videoMatches = videoRegex.findAll(html)
                
                for (match in videoMatches) {
                    var videoUrl = match.value
                    videoUrl = videoUrl.replace("\\/", "/")
                    
                    if (videoUrl.contains(".m3u8")) {
                        M3u8Helper.generateM3u8(
                            source = name,
                            streamUrl = videoUrl,
                            referer = "https://vimeo.com/",
                            headers = mapOf(
                                "Referer" to "https://vimeo.com/",
                                "Origin" to "https://player.vimeo.com"
                            )
                        ).forEach(callback)
                        return true
                    } else if (videoUrl.contains(".mp4")) {
                        newExtractorLink(
                            source = name,
                            name = "Vimeo MP4",
                            url = videoUrl
                        ) {
                            this.referer = "https://vimeo.com/"
                            this.quality = Qualities.P1080.value
                        }?.let { callback(it) }
                        return true
                    }
                }
            }
            false
        } catch (e: Exception) {
            false
        }
    }
    
    // Method 2: Extract from config API
    private suspend fun extractFromConfigApi(videoId: String, referer: String?, callback: (ExtractorLink) -> Unit): Boolean {
        return try {
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
                val json = tryParseJson<VimeoConfig>(text)
                
                json?.request?.files?.let { files ->
                    // Progressive videos (MP4)
                    files.progressive?.forEach { video ->
                        video.url?.let { videoUrl ->
                            val quality = video.quality ?: "unknown"
                            newExtractorLink(
                                source = name,
                                name = "Vimeo $quality",
                                url = videoUrl
                            ) {
                                this.referer = "https://vimeo.com/"
                                this.quality = getQuality(quality)
                            }?.let { callback(it) }
                            return true
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
                        return true
                    }
                }
            }
            false
        } catch (e: Exception) {
            false
        }
    }
    
    // Method 3: Try oEmbed API
    private suspend fun extractFromOEmbed(videoId: String, referer: String?, callback: (ExtractorLink) -> Unit): Boolean {
        return try {
            val oembedUrl = "https://vimeo.com/api/oembed.json?url=https://vimeo.com/$videoId"
            val response = app.get(oembedUrl, headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                "Referer" to (referer ?: "https://www.catsuka.com/")
            ))
            
            if (response.isSuccessful) {
                val json = tryParseJson<Map<String, Any>>(response.text)
                val html = json?.get("html") as? String
                
                if (html != null) {
                    // Extract iframe src from oEmbed HTML
                    val iframeRegex = Regex("""src=["'](https://player\.vimeo\.com/video/\d+[^"']*)["']""")
                    val iframeMatch = iframeRegex.find(html)
                    
                    if (iframeMatch != null) {
                        val iframeUrl = iframeMatch.groupValues[1]
                        // Try Method 1 again with this iframe URL
                        return extractFromPlayerPage(videoId, referer, callback)
                    }
                }
            }
            false
        } catch (e: Exception) {
            false
        }
    }
    
    // Method 4: Fallback to embed
    private fun fallbackToEmbed(videoId: String, callback: (ExtractorLink) -> Unit): Boolean {
        // Use ExtractorLink constructor (not newExtractorLink) since this is not suspend
        callback.invoke(
            ExtractorLink(
                name = name,
                source = name,
                url = "https://player.vimeo.com/video/$videoId",
                referer = "https://vimeo.com/"
            )
        )
        return true
    }
    
    private fun getQuality(quality: String): Int {
        return when {
            quality.contains("4k", true) -> Qualities.P2160.value
            quality.contains("1440", true) -> Qualities.P1440.value
            quality.contains("1080", true) -> Qualities.P1080.value
            quality.contains("720", true) -> Qualities.P720.value
            quality.contains("480", true) -> Qualities.P480.value
            quality.contains("360", true) -> Qualities.P360.value
            quality.contains("240", true) -> Qualities.P240.value
            else -> Qualities.Unknown.value
        }
    }

    // Data classes for player page JSON
    data class VimeoPlayerConfig(
        @JsonProperty("request") val request: VimeoPlayerRequest? = null
    )

    data class VimeoPlayerRequest(
        @JsonProperty("files") val files: VimeoPlayerFiles? = null
    )

    data class VimeoPlayerFiles(
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

    // Data classes for config API JSON
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
