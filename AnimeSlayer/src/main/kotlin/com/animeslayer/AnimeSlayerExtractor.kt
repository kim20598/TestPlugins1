package com.animeslayer.provider

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.loadExtractor

class AnimeSlayerExtractor : ExtractorApi() {
    override val name = "أنمي سلاير استخراج"
    override val mainUrl = "https://animeslayerweb.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            // Try to extract video from the page
            val document = app.get(url, referer = referer ?: mainUrl).document
            
            // Method 1: Direct video links
            val videoLinks = document.select("video source[src], video[src], a[href*='.mp4'], a[href*='.m3u8']")
            videoLinks.forEach { element ->
                val videoUrl = element.attr("src").takeIf { it.isNotBlank() }
                    ?: element.attr("href").takeIf { it.isNotBlank() }
                
                if (videoUrl != null) {
                    val fullUrl = fixUrl(videoUrl)
                    
                    if (fullUrl.contains(".m3u8")) {
                        M3u8Helper.generateM3u8(
                            source = name,
                            streamUrl = fullUrl,
                            referer = mainUrl
                        ).forEach(callback)
                    } else {
                        callback.invoke(
                            newExtractorLink(
                                source = name,
                                name = "فيديو مباشر",
                                url = fullUrl
                            ) {
                                this.referer = mainUrl
                            }
                        )
                    }
                }
            }
            
            // Method 2: Iframe extraction
            document.select("iframe[src]").forEach { iframe ->
                val iframeSrc = iframe.attr("src").takeIf { it.isNotBlank() }
                if (iframeSrc != null) {
                    val fullIframeUrl = fixUrl(iframeSrc)
                    loadExtractor(fullIframeUrl, referer ?: mainUrl, subtitleCallback, callback)
                }
            }
            
            // Method 3: JSON API extraction
            try {
                extractFromScripts(document, url, subtitleCallback, callback)
            } catch (e: Exception) {
                // Ignore script extraction errors
            }
            
        } catch (e: Exception) {
            // Fallback to generic extractor
            loadExtractor(url, referer, subtitleCallback, callback)
        }
    }
    
    private suspend fun extractFromScripts(
        document: org.jsoup.nodes.Document,
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // Look for JSON data in scripts
        document.select("script").forEach { script ->
            val scriptText = script.html()
            
            // Look for video player configuration
            val patterns = listOf(
                Regex("""['"]sources['"]\s*:\s*\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL),
                Regex("""['"]file['"]\s*:\s*['"]([^'"]+)['"]"""),
                Regex("""src\s*:\s*['"]([^'"]+)['"]""")
            )
            
            for (pattern in patterns) {
                val matches = pattern.findAll(scriptText)
                for (match in matches) {
                    val jsonData = match.groupValues[1]
                    if (jsonData.contains("http")) {
                        // Try to parse as JSON array
                        try {
                            val sources = app.tryParseJson<List<VideoSource>>("[$jsonData]")
                            sources?.forEach { source ->
                                source.file?.let { fileUrl ->
                                    val fullUrl = fixUrl(fileUrl)
                                    if (fullUrl.contains(".m3u8")) {
                                        M3u8Helper.generateM3u8(
                                            source = name,
                                            streamUrl = fullUrl,
                                            referer = mainUrl
                                        ).forEach(callback)
                                    } else {
                                        callback.invoke(
                                            newExtractorLink(
                                                source = name,
                                                name = "جودة ${source.label ?: "مجهولة"}",
                                                url = fullUrl
                                            ) {
                                                this.referer = mainUrl
                                                this.quality = source.type?.let { getQualityFromType(it) } ?: 0
                                            }
                                        )
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            // If not JSON array, try as single URL
                            val videoUrl = match.groupValues[1].takeIf { it.isNotBlank() }
                            if (videoUrl != null) {
                                val fullUrl = fixUrl(videoUrl)
                                if (fullUrl.contains(".m3u8")) {
                                    M3u8Helper.generateM3u8(
                                        source = name,
                                        streamUrl = fullUrl,
                                        referer = mainUrl
                                    ).forEach(callback)
                                } else {
                                    callback.invoke(
                                        newExtractorLink(
                                            source = name,
                                            name = "فيديو",
                                            url = fullUrl
                                        ) {
                                            this.referer = mainUrl
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    
    private fun getQualityFromType(type: String): Int {
        return when {
            type.contains("1080") -> Qualities.P1080.value
            type.contains("720") -> Qualities.P720.value
            type.contains("480") -> Qualities.P480.value
            type.contains("360") -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }
    
    private fun fixUrl(url: String): String {
        if (url.isBlank()) return ""
        return when {
            url.startsWith("http") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "$mainUrl$url"
            else -> url
        }
    }
    
    data class VideoSource(
        @JsonProperty("file") val file: String? = null,
        @JsonProperty("label") val label: String? = null,
        @JsonProperty("type") val type: String? = null
    )
}