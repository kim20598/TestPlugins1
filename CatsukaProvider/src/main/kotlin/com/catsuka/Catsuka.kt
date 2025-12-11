package com.catsuka

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLEncoder

class Catsuka : MainAPI() {
    override var mainUrl = "https://www.catsuka.com"
    override var name = "Catsuka"
    override val hasMainPage = true
    override var lang = "en"
    
    // Use external browser to bypass restrictions
    private val bypassUrl = "https://app.zenscrape.com/api/v1/get"
    private val apiKey = "YOUR_ZENSCRAPE_API_KEY" // Get free API key from zenscrape.com
    
    override fun getHeaders(): Map<String, String> {
        return mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.5",
            "Accept-Encoding" to "gzip, deflate, br",
            "Connection" to "keep-alive",
            "Upgrade-Insecure-Requests" to "1",
            "Cache-Control" to "max-age=0"
        )
    }
    
    // Simple main page with fewer categories
    override val mainPage = mainPageOf(
        "$mainUrl/player" to "Catsuka Player",
        "$mainUrl/player/binge/" to "Binge Series",
        "$mainUrl/player/updates/" to "Latest Updates"
    )
    
    private suspend fun fetchWithBypass(url: String): Element {
        return try {
            // Try direct access first
            app.get(url, headers = getHeaders()).document
        } catch (e: Exception) {
            // Fallback to simple scraping
            Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.5")
                .get()
        }
    }
    
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = request.data
        val document = fetchWithBypass(url)
        
        // Simple extraction
        val items = document.select("a[href*='/player/']")
            .mapNotNull { link ->
                val href = link.attr("href")
                if (href.isBlank() || href.contains("categories")) return@mapNotNull null
                
                val title = link.selectFirst("img")?.attr("alt")
                    ?: link.attr("title")
                    ?: link.text().trim()
                
                if (title.isBlank()) return@mapNotNull null
                
                newMovieSearchResponse(title, fixUrl(href), TvType.AnimeMovie) {
                    this.posterUrl = link.selectFirst("img")?.attr("src")?.let { fixUrl(it) }
                }
            }
            .distinctBy { it.url }
            .take(20) // Limit to 20 items
        
        return newHomePageResponse(request.name, items)
    }
    
    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        
        val searchUrl = "$mainUrl/player/?recherche=${URLEncoder.encode(query, "UTF-8")}"
        val document = fetchWithBypass(searchUrl)
        
        return document.select("a[href*='/player/']")
            .mapNotNull { link ->
                val href = link.attr("href")
                if (href.isBlank() || href.contains("categories")) return@mapNotNull null
                
                val title = link.selectFirst("img")?.attr("alt")
                    ?: link.attr("title")
                    ?: link.text().trim()
                
                if (title.isBlank() || !title.contains(query, ignoreCase = true)) {
                    return@mapNotNull null
                }
                
                newMovieSearchResponse(title, fixUrl(href), TvType.AnimeMovie) {
                    this.posterUrl = link.selectFirst("img")?.attr("src")?.let { fixUrl(it) }
                }
            }
            .distinctBy { it.url }
    }
    
    override suspend fun load(url: String): LoadResponse {
        val document = fetchWithBypass(url)
        
        val title = document.selectFirst("h1, .video-title")?.text()?.trim()
            ?: "Catsuka Video"
        
        val poster = document.selectFirst("img")?.attr("src")?.let { fixUrl(it) }
        
        return newMovieLoadResponse(title, url, TvType.AnimeMovie, url) {
            this.posterUrl = poster
        }
    }
    
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = fetchWithBypass(data)
        
        // Look for any video source
        val videoElement = document.selectFirst("video source[src], video[src]")
        val videoUrl = videoElement?.attr("src")
        
        if (videoUrl != null && videoUrl.isNotBlank()) {
            callback(
                ExtractorLink(
                    source = name,
                    name = "Direct Video",
                    url = fixUrl(videoUrl),
                    referer = data,
                    quality = Qualities.Unknown.value,
                    type = ExtractorLinkType.VIDEO
                )
            )
            return true
        }
        
        return false
    }
    
    private fun fixUrl(url: String): String {
        if (url.isBlank()) return ""
        return when {
            url.startsWith("http") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "$mainUrl$url"
            else -> "$mainUrl/$url"
        }
    }
}
