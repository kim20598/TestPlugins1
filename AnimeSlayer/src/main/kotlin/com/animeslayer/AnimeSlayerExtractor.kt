package com.animeslayer.provider

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.Qualities

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
                            ExtractorLink(
                                source = name,
                                name = "فيديو مباشر",
                                url = fullUrl,
                                referer = mainUrl,
                                quality = Qualities.Unknown.value
                            )
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
            
        } catch (e: Exception) {
            // Fallback to generic extractor
            loadExtractor(url, referer, subtitleCallback, callback)
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
}
