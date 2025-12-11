package com.catsuka

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLEncoder

class Catsuka : MainAPI() {
    override var mainUrl = "https://www.catsuka.com"
    override var name = "Catsuka"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(
        TvType.AnimeMovie,
        TvType.Anime,
        TvType.OVA
    )
    
    private val playerUrl = "$mainUrl/player"
    private val categoriesUrl = "$mainUrl/player/categories/"

    override val mainPage = mainPageOf(
        playerUrl to "📺 Catsuka Home",
        categoriesUrl to "📁 All Categories",
        "$mainUrl/player/highlights/" to "⭐ Animator Highlights",
        "$mainUrl/player/updates/" to "🆕 Latest Updates",
        "$mainUrl/player/binge/" to "🍿 Binge! Anime Series",
        "$playerUrl/?recherche=&sort=views" to "🔥 Most Viewed",
        "$mainUrl/player/categorie/courtmetrage" to "🎬 Short Films",
        "$mainUrl/player/categorie/pilote" to "✈️ Pilots",
        "$mainUrl/player/categorie/episode" to "📺 Episodes",
        "$mainUrl/player/categorie/clip" to "🎵 Music Videos",
        "$mainUrl/player/categorie/pub" to "📢 Commercials",
        "$mainUrl/player/categorie/cinematique" to "🎮 Cinematics",
        "$mainUrl/player/categorie/opening" to "🎭 Openings",
        "$mainUrl/player/categorie/trailer" to "🎥 Trailers",
        "$mainUrl/player/categorie/extrait" to "📖 Excerpts",
        "$mainUrl/player/categorie/demoreel" to "🎨 Demoreels",
        "$mainUrl/player/categorie/sakuga" to "🇯🇵 Sakuga",
        "$mainUrl/player/categorie/makingof" to "🔧 Making Of",
        "$mainUrl/player/categorie/parodies" to "❤️ Tributes",
        "$mainUrl/player/categorie/autres" to "📦 Others",
        "$mainUrl/player/categorie/nanars" to "😂 Junk",
        "$mainUrl/player/categorie/catsukanolife" to "📡 Catsuka TV Show",
        "$mainUrl/player/categorie/catsukatrailers" to "🎞️ Movie Trailers"
    )

    // Browser headers to avoid CGU page
    private fun getBrowserHeaders(referer: String = ""): Map<String, String> {
        val headers = mutableMapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.9,fr;q=0.8",
            "Accept-Encoding" to "gzip, deflate, br",
            "Connection" to "keep-alive",
            "Upgrade-Insecure-Requests" to "1",
            "Sec-Fetch-Dest" to "document",
            "Sec-Fetch-Mode" to "navigate",
            "Sec-Fetch-Site" to "same-origin",
            "Cache-Control" to "max-age=0"
        )
        
        if (referer.isNotEmpty()) {
            headers["Referer"] = referer
        }
        
        return headers
    }

    // Helper function to fetch pages with proper headers
    private suspend fun getDocument(url: String, referer: String = playerUrl): Element {
        return app.get(url, headers = getBrowserHeaders(referer)).document
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = request.data
        
        return try {
            val pageUrl = if (page > 1) "$url?page=$page" else url
            val document = getDocument(pageUrl, playerUrl)
            
            // Check if we got CGU page
            val pageText = document.text()
            if (pageText.contains("General conditions of use") || 
                pageText.contains("Conditions d'utilisation") ||
                document.select("h1, h2").any { 
                    it.text().contains("CGU", ignoreCase = true) || 
                    it.text().contains("Conditions", ignoreCase = true) 
                }) {
                
                // Try alternative approach with minimal headers
                val altDoc = app.get(pageUrl, headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                )).document
                
                val items = extractItemsFromDocument(altDoc, url)
                return newHomePageResponse(
                    request.name,
                    items.distinctBy { it.url },
                    hasNext = items.isNotEmpty() && url != playerUrl
                )
            }
            
            val items = extractItemsFromDocument(document, url)
            newHomePageResponse(
                request.name,
                items.distinctBy { it.url },
                hasNext = items.isNotEmpty() && url != playerUrl
            )
            
        } catch (e: Exception) {
            newHomePageResponse(request.name, emptyList())
        }
    }

    private fun extractItemsFromDocument(document: Element, baseUrl: String): List<SearchResponse> {
        val items = mutableListOf<SearchResponse>()
        
        // Method 1: Swiper slides
        items.addAll(document.select(".swiper-slide").mapNotNull { slide ->
            slide.toSearchResponse()
        })
        
        // Method 2: Video items
        items.addAll(document.select(".video-item, .item.video, .video-card").mapNotNull { item ->
            item.toSearchResponse()
        })
        
        // Method 3: Direct links
        items.addAll(document.select("a[href*='/player/']").filterNot { 
            val href = it.attr("href")
            href.contains("categories") || href.contains("cgu") || href.contains("highlights")
        }.mapNotNull { link ->
            val href = link.attr("href")
            if (href.isBlank()) return@mapNotNull null
            
            val fullUrl = fixUrl(href)
            val title = link.attr("title")
                ?: link.selectFirst("img")?.attr("alt")
                ?: link.selectFirst(".title, .name, h3, h4, span")?.text()
                ?: link.text().trim()
            
            if (title.isBlank() || title.contains("CGU", ignoreCase = true)) return@mapNotNull null
            
            val poster = link.selectFirst("img")?.attr("src")?.let { fixUrl(it) }
                ?: link.selectFirst("img")?.attr("data-src")?.let { fixUrl(it) }
            
            newMovieSearchResponse(title, fullUrl, TvType.AnimeMovie) {
                this.posterUrl = poster
            }
        })
        
        return items.distinctBy { it.url }
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        // Find video link
        val link = this.selectFirst("a[href*='/player/']") ?: return null
        val href = link.attr("href")
        
        if (href.isBlank() || href.contains("categories") || href.contains("cgu")) {
            return null
        }
        
        val fullUrl = fixUrl(href)
        
        // Extract title
        val title = this.selectFirst(".video-title, .title, h3, h4, .name")?.text()?.trim()
            ?: link.attr("title")
            ?: link.selectFirst("img")?.attr("alt")
            ?: link.text().trim()
        
        if (title.isBlank() || title.contains("CGU", ignoreCase = true)) {
            return null
        }
        
        // Extract thumbnail
        val poster = this.selectFirst("img")?.attr("src")?.let { fixUrl(it) }
            ?: this.selectFirst("img")?.attr("data-src")?.let { fixUrl(it) }
            ?: link.selectFirst("img")?.attr("src")?.let { fixUrl(it) }
            ?: link.selectFirst("img")?.attr("data-src")?.let { fixUrl(it) }
        
        // Determine type
        val tvType = when {
            title.contains("Movie", ignoreCase = true) || href.contains("/movie/") -> TvType.AnimeMovie
            title.contains("Season", ignoreCase = true) || href.contains("/videos/") -> TvType.Anime
            else -> TvType.OVA
        }
        
        return newMovieSearchResponse(title, fullUrl, tvType) {
            this.posterUrl = poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        
        val searchUrl = "$playerUrl/?recherche=${URLEncoder.encode(query, "UTF-8")}"
        val document = getDocument(searchUrl, playerUrl)
        
        return extractItemsFromDocument(document, searchUrl).filter { 
            it.name.contains(query, ignoreCase = true)
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = getDocument(url, playerUrl)
        
        // Extract metadata
        val title = document.selectFirst("meta[property='og:title']")?.attr("content")
            ?: document.selectFirst("h1")?.text()?.trim()
            ?: document.selectFirst(".video-title")?.text()?.trim()
            ?: "Catsuka Video"
        
        val description = document.selectFirst("meta[property='og:description']")?.attr("content")
            ?: document.selectFirst("meta[name=description]")?.attr("content")
            ?: document.selectFirst(".video-description, .description")?.text()?.trim()
            ?: "Animation video from Catsuka Player"
        
        val poster = document.selectFirst("meta[property='og:image']")?.attr("content")?.let { fixUrl(it) }
            ?: document.selectFirst(".video-poster img")?.attr("src")?.let { fixUrl(it) }
            ?: document.selectFirst("img[src*='thumbnail']")?.attr("src")?.let { fixUrl(it) }
            ?: document.selectFirst("img")?.attr("src")?.let { fixUrl(it) }
        
        return newMovieLoadResponse(title, url, TvType.AnimeMovie, url) {
            this.posterUrl = poster
            this.plot = description
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = getDocument(data, playerUrl)
        var foundLinks = false
        
        // Direct video sources
        document.select("video source[src], video[src], [data-video-src]").forEach { source ->
            val videoUrl = source.attr("src")
                .ifBlank { source.attr("data-src") }
                .ifBlank { source.attr("data-video-src") }
            
            if (videoUrl.isNotBlank()) {
                foundLinks = true
                callback(
                    ExtractorLink(
                        source = name,
                        name = "Direct Video",
                        url = fixUrl(videoUrl),
                        referer = data,
                        quality = Qualities.Unknown.value,
                        type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 
                               else ExtractorLinkType.VIDEO
                    )
                )
            }
        }
        
        // If no direct links, check for embedded players
        if (!foundLinks) {
            document.select("iframe[src*='youtube'], iframe[src*='vimeo']").forEach { iframe ->
                val iframeSrc = iframe.attr("src")
                if (iframeSrc.isNotBlank()) {
                    foundLinks = true
                    loadExtractor(fixUrl(iframeSrc), data, subtitleCallback, callback)
                }
            }
        }
        
        return foundLinks
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
