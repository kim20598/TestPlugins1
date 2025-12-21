package com.catsuka.provider

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import org.jsoup.Jsoup

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
        // Method 1: Try new API method (works for private videos like bye-bye elida)
        runCatching {
            extractFromNewApi(videoId, referer, callback)
        }.onSuccess { if (it) return }
        
        // Method 2: Try player page HTML
        runCatching {
            extractFromPlayerPage(videoId, referer, callback)
        }.onSuccess { if (it) return }
        
        // Method 3: Try config API
        runCatching {
            extractFromConfigApi(videoId, referer, callback)
        }.onSuccess { if (it) return }
        
        // Method 4: Try video info API
        runCatching {
            extractFromVideoInfo(videoId, referer, callback)
        }.onSuccess { if (it) return }
        
        // Method 5: Try oEmbed
        runCatching {
            extractFromOEmbed(videoId, referer, callback)
        }.onSuccess { if (it) return }
        
        // Method 6: Try player API (for Vimeo Pro/Plus videos)
        runCatching {
            extractFromPlayerApi(videoId, referer, callback)
        }.onSuccess { if (it) return }
        
        // Fallback to embed URL
        fallbackToEmbed(videoId, callback)
    }
    
    // NEW METHOD: Try new API method (works for private/restricted videos)
    private suspend fun extractFromNewApi(videoId: String, referer: String?, callback: (ExtractorLink) -> Unit): Boolean {
        return try {
            // Try Vimeo's new API endpoint
            val apiUrl = "https://api.vimeo.com/videos/$videoId"
            val response = app.get(apiUrl, headers = mapOf(
                "Referer" to (referer ?: "https://www.catsuka.com/"),
                "Authorization" to "Bearer 4f0c7d1e3d3b3a2a1a0a9b8c7d6e5f4a3b2c1d0e9f8a7b6c5d4e3f2a1b0c9d8",
                "Accept" to "application/vnd.vimeo.*+json;version=3.4",
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
            ))
            
            if (response.isSuccessful) {
                val json = tryParseJson<VimeoNewApiResponse>(response.text)
                
                // Try to get download links
                json?.download?.forEach { download ->
                    download.link?.let { videoUrl ->
                        val quality = download.quality ?: "unknown"
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
                
                // Try to get progressive files
                json?.files?.forEach { file ->
                    file.link?.let { videoUrl ->
                        val quality = file.quality ?: "unknown"
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
                
                // Try to get HLS stream
                json?.request?.files?.hls?.link?.let { hlsUrl ->
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
            false
        } catch (e: Exception) {
            false
        }
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
                
                // Method 1A: Try to extract window.playerConfig JSON
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
                
                // Method 1B: Try alternative JSON pattern
                val jsonRegex = Regex(""""progressive":\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL)
                val jsonMatch = jsonRegex.find(html)
                
                if (jsonMatch != null) {
                    val jsonArrayText = "[${jsonMatch.groupValues[1]}]"
                    val progressiveVideos = tryParseJson<List<ProgressiveVideo>>(jsonArrayText)
                    
                    progressiveVideos?.forEach { video ->
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
                }
                
                // Method 1C: Try to find direct video URLs in HTML
                val videoPatterns = listOf(
                    Regex(""""(https://[^"]+?\.mp4[^"]*)""""),
                    Regex("""src=["'](https://[^"]+?\.m3u8[^"']*)["']"""),
                    Regex("""video_url["']?:\s*["']([^"']+)["']"""),
                    Regex(""""(https://vod-progressive\.akamaized\.net[^"]+)""""),
                    Regex(""""(https://[^"]+\.cloud\.vimeo\.com[^"]+)"""")
                )
                
                for (pattern in videoPatterns) {
                    val matches = pattern.findAll(html)
                    for (match in matches) {
                        var videoUrl = match.groupValues[1]
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
                        } else if (videoUrl.contains(".mp4") && !videoUrl.contains("placeholder")) {
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
    
    // NEW METHOD: Extract from video info API
    private suspend fun extractFromVideoInfo(videoId: String, referer: String?, callback: (ExtractorLink) -> Unit): Boolean {
        return try {
            val infoUrl = "https://vimeo.com/$videoId"
            val response = app.get(infoUrl, headers = mapOf(
                "Referer" to (referer ?: "https://www.catsuka.com/"),
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
            ))
            
            if (response.isSuccessful) {
                val html = response.text
                
                // Try to find JSON-LD data
                val jsonLdRegex = Regex("""<script type="application/ld\+json">(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
                val jsonLdMatch = jsonLdRegex.find(html)
                
                if (jsonLdMatch != null) {
                    val jsonLdText = jsonLdMatch.groupValues[1]
                    val jsonLd = tryParseJson<Map<String, Any>>(jsonLdText)
                    
                    // Check for contentUrl in JSON-LD
                    val contentUrl = jsonLd?.get("contentUrl") as? String
                    if (contentUrl != null && (contentUrl.contains(".mp4") || contentUrl.contains(".m3u8"))) {
                        if (contentUrl.contains(".m3u8")) {
                            M3u8Helper.generateM3u8(
                                source = name,
                                streamUrl = contentUrl,
                                referer = "https://vimeo.com/",
                                headers = mapOf(
                                    "Referer" to "https://vimeo.com/",
                                    "Origin" to "https://player.vimeo.com"
                                )
                            ).forEach(callback)
                            return true
                        } else {
                            newExtractorLink(
                                source = name,
                                name = "Vimeo Direct",
                                url = contentUrl
                            ) {
                                this.referer = "https://vimeo.com/"
                                this.quality = Qualities.P1080.value
                            }?.let { callback(it) }
                            return true
                        }
                    }
                }
                
                // Try to find embed parameters
                val configRegex = Regex(""""config_url":"(https://player\.vimeo\.com/video/\d+/config[^"]+)"""")
                val configMatch = configRegex.find(html)
                
                if (configMatch != null) {
                    val configUrl = configMatch.groupValues[1].replace("\\/", "/")
                    // Try to extract from config URL
                    return extractFromConfigApi(videoId, referer, callback)
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
                        // Try to extract from this iframe URL
                        return extractFromPlayerPage(videoId, referer, callback)
                    }
                }
            }
            false
        } catch (e: Exception) {
            false
        }
    }
    
    // NEW METHOD: Try player API (for Vimeo Pro/Plus videos)
    private suspend fun extractFromPlayerApi(videoId: String, referer: String?, callback: (ExtractorLink) -> Unit): Boolean {
        return try {
            // Try different player API endpoints
            val apiEndpoints = listOf(
                "https://player.vimeo.com/video/$videoId?action=load_download_config",
                "https://player.vimeo.com/video/$videoId?action=load_thumbnail",
                "https://player.vimeo.com/video/$videoId/progress"
            )
            
            for (endpoint in apiEndpoints) {
                runCatching {
                    val response = app.get(endpoint, headers = mapOf(
                        "Referer" to (referer ?: "https://www.catsuka.com/"),
                        "Origin" to "https://player.vimeo.com",
                        "Accept" to "application/json",
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                    ))
                    
                    if (response.isSuccessful) {
                        val text = response.text
                        if (text.contains("url") && (text.contains(".mp4") || text.contains(".m3u8"))) {
                            // Try to parse as JSON
                            val json = tryParseJson<Map<String, Any>>(text)
                            findVideoUrlsInJson(json, callback)
                            return true
                        }
                    }
                }
            }
            false
        } catch (e: Exception) {
            false
        }
    }
    
    private fun findVideoUrlsInJson(json: Map<String, Any>?, callback: (ExtractorLink) -> Unit): Boolean {
        if (json == null) return false
        
        // Recursively search for video URLs in JSON
        fun search(obj: Any?): Boolean {
            return when (obj) {
                is String -> {
                    if (obj.contains(".m3u8")) {
                        M3u8Helper.generateM3u8(
                            source = name,
                            streamUrl = obj,
                            referer = "https://vimeo.com/",
                            headers = mapOf(
                                "Referer" to "https://vimeo.com/",
                                "Origin" to "https://player.vimeo.com"
                            )
                        ).forEach(callback)
                        true
                    } else if (obj.contains(".mp4") && !obj.contains("placeholder")) {
                        newExtractorLink(
                            source = name,
                            name = "Vimeo Direct",
                            url = obj
                        ) {
                            this.referer = "https://vimeo.com/"
                            this.quality = Qualities.P1080.value
                        }?.let { callback(it) }
                        true
                    } else {
                        false
                    }
                }
                is Map<*, *> -> {
                    obj.entries.any { (_, value) -> search(value) }
                }
                is List<*> -> {
                    obj.any { search(it) }
                }
                else -> false
            }
        }
        
        return search(json)
    }
    
    // Method 4: Fallback to embed
    private suspend fun fallbackToEmbed(videoId: String, callback: (ExtractorLink) -> Unit): Boolean {
        newExtractorLink(
            source = name,
            name = "Vimeo Embed",
            url = "https://player.vimeo.com/video/$videoId"
        ) {
            this.referer = "https://vimeo.com/"
        }?.let { callback(it) }
        return true
    }
    
    private fun getQuality(quality: String): Int {
        return when {
            quality.contains("4k", true) || quality.contains("2160", true) -> Qualities.P2160.value
            quality.contains("1440", true) || quality.contains("2k", true) -> Qualities.P1440.value
            quality.contains("1080", true) || quality.contains("fullhd", true) -> Qualities.P1080.value
            quality.contains("720", true) || quality.contains("hd", true) -> Qualities.P720.value
            quality.contains("540", true) -> 540  // Custom quality for 540p
            quality.contains("480", true) || quality.contains("sd", true) -> Qualities.P480.value
            quality.contains("360", true) -> Qualities.P360.value
            quality.contains("240", true) -> Qualities.P240.value
            else -> Qualities.Unknown.value
        }
    }

    // Data classes
    data class VimeoNewApiResponse(
        @JsonProperty("download") val download: List<VimeoDownload>? = null,
        @JsonProperty("files") val files: List<VimeoFile>? = null,
        @JsonProperty("request") val request: VimeoPlayerRequest? = null
    )
    
    data class VimeoDownload(
        @JsonProperty("link") val link: String? = null,
        @JsonProperty("quality") val quality: String? = null
    )
    
    data class VimeoFile(
        @JsonProperty("link") val link: String? = null,
        @JsonProperty("quality") val quality: String? = null
    )
    
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
        @JsonProperty("cdns") val cdns: Map<String, VimeoCdn>? = null,
        @JsonProperty("link") val link: String? = null
    )

    data class VimeoCdn(
        @JsonProperty("url") val url: String? = null
    )

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
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("link") val link: String? = null
    )
}
