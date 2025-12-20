package com.catsuka.provider

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLEncoder

class Catsuka : MainAPI() {
    override var mainUrl = "https://www.catsuka.com"
    override var name = "Catsuka Player"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.Cartoon,
        TvType.OVA
    )

    // FIXED: All categories with CORRECT video listing URLs (not legal pages)
    override val mainPage = mainPageOf(
        // Main navigation - use player root or actual video pages
        "$mainUrl/player/" to "🎬 Home/Highlights",
        "$mainUrl/player/updates/" to "🆕 Latest Updates",
        
        // Video Categories - using CORRECT video listing URLs
        // From HTML: player/categorie/courtmetrage shows videos in swiper-slide
        "$mainUrl/player/" to "🎥 All Videos", // Main player page has all categories
        "$mainUrl/player/?recherche=" to "🔍 Search", // Search page
        
        // Alternative approach: Use the main player page sections
        // These are actual video listing pages from the HTML structure
        "$mainUrl/player/" to "✨ Featured Videos",
        "$mainUrl/player/highlights/" to "🌟 Artist Highlights",
        
        // Specific video pages from the HTML example
        "$mainUrl/player/the_primary_cilia" to "📹 Example: The Primary Cilia",
        "$mainUrl/player/vorace" to "📹 Example: Vorace",
        "$mainUrl/player/snow_bear" to "📹 Example: Snow Bear",
        
        // Binge Categories - These are actual video listing pages
        "$mainUrl/player/binge/category-animepost2000_watchable-nofilter_sort-rank/" to "🇯🇵 Anime 2000s",
        "$mainUrl/player/binge/category-anime1990_watchable-nofilter_sort-rank/" to "🇯🇵 Anime 1990s",
        "$mainUrl/player/binge/category-anime1980_watchable-nofilter_sort-rank/" to "🇯🇵 Anime 1980s",
        
        // Highlight pages (these show actual videos)
        "$mainUrl/player/highlight/jonathan_djob_nkondo" to "🎨 Artist: Jonathan Djob Nkondo"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page > 1) {
            // Handle pagination for specific URLs
            when {
                request.data.contains("?") -> "${request.data}&page=$page"
                request.data.endsWith("/") -> "${request.data}page/$page/"
                else -> "${request.data}?page=$page"
            }
        } else {
            request.data
        }
        
        try {
            val doc = app.get(url, timeout = 30).document
            
            // Check if this is a legal page (contains CGU/GTC or privacy)
            val pageText = doc.text()
            if (pageText.contains("General conditions", ignoreCase = true) ||
                pageText.contains("Terms of Service", ignoreCase = true) ||
                pageText.contains("Privacy Policy", ignoreCase = true) ||
                pageText.contains("CGU/GTC", ignoreCase = true)) {
                
                // This is a legal page, return empty
                return newHomePageResponse(request.name, emptyList())
            }
            
            val items = parseVideoItems(doc)
            
            return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
        } catch (e: Exception) {
            return newHomePageResponse(request.name, emptyList())
        }
    }

    private fun parseVideoItems(doc: Element): List<SearchResponse> {
        val items = mutableListOf<SearchResponse>()
        
        // STRATEGY 1: Look for video swiper slides (from HTML example)
        doc.select(".swiper-slide a").forEach { element ->
            val href = fixUrl(element.attr("href"))
            if (isValidVideoUrl(href)) {
                val title = element.selectFirst("span")?.text()?.trim()
                    ?: element.attr("title")
                    ?: element.selectFirst("img")?.attr("alt") ?: ""
                
                if (title.isNotBlank()) {
                    val poster = fixUrlNull(
                        element.selectFirst("img")?.attr("src")
                            ?: element.selectFirst("img")?.attr("data-src")
                    )
                    
                    items.add(newMovieSearchResponse(title, href, TvType.Movie) {
                        this.posterUrl = poster
                    })
                }
            }
        }
        
        // STRATEGY 2: Look for video items in highlights
        if (items.isEmpty()) {
            doc.select(".zonetableau li a").forEach { element ->
                val href = fixUrl(element.attr("href"))
                if (isValidVideoUrl(href)) {
                    val title = element.selectFirst("b")?.text()?.trim()
                        ?: element.text().trim()
                    
                    if (title.isNotBlank()) {
                        val poster = fixUrlNull(
                            element.selectFirst("img")?.attr("src")
                        )
                        
                        items.add(newMovieSearchResponse(title, href, TvType.Movie) {
                            this.posterUrl = poster
                        })
                    }
                }
            }
        }
        
        // STRATEGY 3: Look for any video links in the page
        if (items.isEmpty()) {
            doc.select("a[href*='/player/']").forEach { element ->
                val href = fixUrl(element.attr("href"))
                if (isValidVideoUrl(href) && !href.contains("/cgu/") && !href.contains("/privacy/")) {
                    val title = element.text().trim()
                    if (title.isNotBlank() && title.length > 2 && !title.contains("CGU") && !title.contains("Privacy")) {
                        val poster = fixUrlNull(
                            element.selectFirst("img")?.attr("src")
                                ?: element.selectFirst("img")?.attr("data-src")
                        )
                        
                        items.add(newMovieSearchResponse(title, href, TvType.Movie) {
                            this.posterUrl = poster
                        })
                    }
                }
            }
        }
        
        return items.distinctBy { it.url }.take(20) // Limit to 20 items
    }
    
    private fun isValidVideoUrl(url: String): Boolean {
        return url.contains("/player/") && 
               !url.contains("/cgu/") && 
               !url.contains("/privacy/") &&
               !url.contains("/categorie/") &&
               !url.contains("/binge/category-") &&
               !url.contains("/tag/") &&
               !url.contains("?lang=") &&
               !url.endsWith("/player/") &&
               url != "$mainUrl/player/"
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val searchUrl = "$mainUrl/player/?recherche=$encodedQuery"
        
        try {
            val doc = app.get(searchUrl, timeout = 30).document
            return parseVideoItems(doc)
        } catch (e: Exception) {
            return emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        try {
            val doc = app.get(url, timeout = 30).document
            
            // Check if this is a legal page
            val pageText = doc.text()
            if (pageText.contains("General conditions", ignoreCase = true) ||
                pageText.contains("Terms of Service", ignoreCase = true)) {
                // Return error for legal pages
                return newMovieLoadResponse("Legal Page", url, TvType.Movie, url) {
                    this.plot = "This is a legal terms page, not a video. Try a different URL."
                }
            }
            
            val title = doc.selectFirst("h1, .txtblanc25, .post__name, .entry-title")?.text()?.trim() 
                ?: "Unknown Title"
            
            val poster = fixUrlNull(
                doc.selectFirst("meta[property='og:image']")?.attr("content")
                    ?: doc.selectFirst("meta[itemprop='image']")?.attr("content")
                    ?: doc.selectFirst("img.lazy")?.attr("data-src")
                    ?: doc.selectFirst(".poster img, .cover img")?.attr("src")
                    ?: doc.selectFirst("img")?.attr("src")
            )
            
            // Extract info from the HTML structure you provided
            var director: String? = null
            var production: String? = null
            var dateAdded: String? = null
            
            // From the HTML example: <span class="txtorange17"><b>Director : </b><b>Tom Rameaux, Armand Goxe</b>
            doc.select("span.txtorange17 b, .txtorange17 b").forEach { b ->
                val text = b.text()
                if (text.contains("Director", ignoreCase = true)) {
                    director = b.nextSibling()?.toString()?.trim()
                        ?: b.parent()?.text()?.substringAfter("Director")?.trim()
                }
                if (text.contains("Production", ignoreCase = true)) {
                    production = b.nextSibling()?.toString()?.trim()
                        ?: b.parent()?.text()?.substringAfter("Production")?.trim()
                }
            }
            
            // Extract date: "Video added on 2025 November 18th"
            doc.select("span.txtblanc14, span.txtblanc12").forEach { span ->
                val text = span.text()
                if (text.contains("added on", ignoreCase = true) || text.contains("Video added", ignoreCase = true)) {
                    dateAdded = text.trim()
                }
            }
            
            val tags = doc.select("a[href*='/player/tag/']").map { it.text().trim() }
            
            val plotBuilder = StringBuilder()
            if (director != null) plotBuilder.append("🎬 Director: $director\n")
            if (production != null) plotBuilder.append("🏢 Production: $production\n")
            if (dateAdded != null) plotBuilder.append("📅 $dateAdded\n")
            
            // Get description
            val description = doc.selectFirst(".videosinfos_left")?.text()?.substringAfter(dateAdded ?: "")?.trim()
                ?: doc.selectFirst("meta[name='description']")?.attr("content")
            
            if (description != null) plotBuilder.append("\n$description")
            
            // Check for episodes/playlist (from universal video player)
            val episodes = mutableListOf<Episode>()
            doc.select(".universal_video_player_list li").forEachIndexed { index, li ->
                val videoId = li.attr("data-vimeo") ?: li.attr("data-youtube")
                val epTitle = li.attr("data-title") ?: "Video ${index + 1}"
                
                if (videoId != null) {
                    episodes.add(newEpisode(videoId) {
                        this.name = epTitle
                        this.episode = index + 1
                    })
                }
            }
            
            if (episodes.size > 1) {
                return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                    this.posterUrl = poster
                    this.plot = plotBuilder.toString().trim()
                    this.tags = tags
                }
            }
            
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plotBuilder.toString().trim()
                this.tags = tags
            }
            
        } catch (e: Exception) {
            return newMovieLoadResponse("Error Loading", url, TvType.Movie, url) {
                this.plot = "Failed to load: ${e.message}"
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Same as before...
        return when {
            data.matches(Regex("\\d+")) -> {
                val vimeoUrl = "https://player.vimeo.com/video/$data"
                loadExtractor(vimeoUrl, "$mainUrl/player/", subtitleCallback, callback)
                true
            }
            data.matches(Regex("[A-Za-z0-9_-]{11}")) -> {
                val youtubeUrl = "https://www.youtube.com/watch?v=$data"
                loadExtractor(youtubeUrl, "$mainUrl/player/", subtitleCallback, callback)
                true
            }
            data.startsWith("http") -> {
                loadExtractor(data, "$mainUrl/player/", subtitleCallback, callback)
                true
            }
            else -> {
                try {
                    val doc = app.get(data).document
                    
                    val iframe = doc.selectFirst("iframe[src*='vimeo'], iframe[src*='youtube']")
                    if (iframe != null) {
                        val src = fixUrl(iframe.attr("src"))
                        return loadExtractor(src, data, subtitleCallback, callback)
                    }
                    
                    false
                } catch (e: Exception) {
                    false
                }
            }
        }
    }

    private fun fixUrl(url: String): String {
        return when {
            url.isEmpty() -> ""
            url.startsWith("http") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "$mainUrl$url"
            else -> "$mainUrl/$url"
        }
    }

    private fun fixUrlNull(url: String?): String? {
        return url?.let { fixUrl(it) }
    }
}
