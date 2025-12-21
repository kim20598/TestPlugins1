package com.catsuka.provider

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

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
        TvType.OVA
    )

    // UPDATED: All main page sections
    override val mainPage = mainPageOf(
        "$mainUrl/player/" to "All Videos",
        "$mainUrl/player/highlights/" to "Highlights",
        "$mainUrl/player/updates/" to "Updates",
        "$mainUrl/player/binge/" to "BINGE!",
        "$mainUrl/player/categories/" to "Categories",
        "$mainUrl/player/categorie/courtmetrage" to "Short Films",
        "$mainUrl/player/categorie/clip" to "Music Videos",
        "$mainUrl/player/categorie/pub" to "Commercials",
        "$mainUrl/player/categorie/cinematique" to "Cinematics",
        "$mainUrl/player/categorie/opening" to "Openings",
        "$mainUrl/player/categorie/trailer" to "Trailers",
        "$mainUrl/player/categorie/extrait" to "Excerpts",
        "$mainUrl/player/categorie/demoreel" to "Demo Reels",
        "$mainUrl/player/categorie/sakuga" to "Sakuga",
        "$mainUrl/player/categorie/makingof" to "Making Of",
        "$mainUrl/player/categorie/parodies" to "Tributes",
        "$mainUrl/player/categorie/autres" to "Others",
        "$mainUrl/player/categorie/nanars" to "Junk",
        "$mainUrl/player/categorie/catsukatrailers" to "Movie Trailers",
        "$mainUrl/player/categorie/pilote" to "Pilots",
        "$mainUrl/player/categorie/episode" to "Episodes"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        return try {
            val url = request.data + if (page > 1) "/$page" else ""
            val document = app.get(url).document
            
            val items = when {
                // BINGE! page (TV series) - special handling
                url.contains("/binge/") -> {
                    document.select(".swiper-slide").mapNotNull { element ->
                        parseBingeItem(element)
                    }
                }
                // Category pages (like Short Films, Music Videos, etc.)
                url.contains("/categorie/") -> {
                    parseCategoryPage(document)
                }
                // Highlights page
                url.contains("/highlight/") || url.contains("/highlights") -> {
                    parseHighlightsPage(document)
                }
                // All other pages (main page, updates, etc.)
                else -> {
                    parseMainOrUpdatesPage(document)
                }
            }
            
            newHomePageResponse(request.name, items, hasNext = items.isNotEmpty() && hasNextPage(document, page))
        } catch (e: Exception) {
            e.printStackTrace()
            newHomePageResponse(request.name, emptyList())
        }
    }
    
    // NEW: Parse category pages (like Short Films, Music Videos, etc.)
    private fun parseCategoryPage(document: org.jsoup.nodes.Document): List<SearchResponse> {
        val items = mutableListOf<SearchResponse>()
        
        // Method 1: Look for #tableau li elements (category page structure)
        document.select("#tableau li").forEach { element ->
            parseTableauItem(element)?.let { items.add(it) }
        }
        
        // Method 2: Fallback to swiper slides
        if (items.isEmpty()) {
            document.select(".swiper-slide").forEach { element ->
                parseSwiperSlide(element)?.let { items.add(it) }
            }
        }
        
        return items.distinctBy { it.url }
    }
    
    // NEW: Parse tableau items (category page specific)
    private fun parseTableauItem(element: Element): SearchResponse? {
        // Get link from image
        val imgLink = element.selectFirst("a") ?: return null
        val href = imgLink.attr("href").takeIf { it.isNotBlank() } ?: return null
        val fixedHref = fixUrl(href)
        
        // Get title from span > a > b
        val titleElement = element.selectFirst("span a b")
        val title = titleElement?.text()?.trim() ?: return null
        
        // Get thumbnail
        val img = element.selectFirst("img")
        val posterUrl = img?.attr("src")?.let { 
            if (it.startsWith("http")) it else "$mainUrl/$it".removePrefix("$mainUrl//")
        }
        
        return newAnimeSearchResponse(title, fixedHref) {
            this.posterUrl = posterUrl
        }
    }
    
    // NEW: Parse highlights page
    private fun parseHighlightsPage(document: org.jsoup.nodes.Document): List<SearchResponse> {
        val items = mutableListOf<SearchResponse>()
        
        // Try multiple selectors for highlights
        document.select(".swiper-slide, .item.video, #tableau li").forEach { element ->
            parseSwiperSlide(element)?.let { items.add(it) }
            parseTableauItem(element)?.let { items.add(it) }
        }
        
        return items.distinctBy { it.url }
    }
    
    // NEW: Parse main page or updates page
    private fun parseMainOrUpdatesPage(document: org.jsoup.nodes.Document): List<SearchResponse> {
        val items = mutableListOf<SearchResponse>()
        
        // Try all possible selectors
        document.select(".swiper-slide, .item.video, #tableau li").forEach { element ->
            parseSwiperSlide(element)?.let { items.add(it) }
            parseTableauItem(element)?.let { items.add(it) }
        }
        
        return items.distinctBy { it.url }
    }
    
    // NEW: Check if there's a next page
    private fun hasNextPage(document: org.jsoup.nodes.Document, currentPage: Int): Boolean {
        // Look for pagination
        val pagination = document.select(".txtpagination, .pagination").text()
        
        // Check if there are page numbers after current page
        return when {
            pagination.contains("Page") && pagination.contains("/") -> {
                val match = Regex("""Page\s+\d+\s+/\s+(\d+)""").find(pagination)
                val totalPages = match?.groupValues?.get(1)?.toIntOrNull() ?: 0
                currentPage < totalPages
            }
            document.select("a[href*='?page='], a[href*='/2']").isNotEmpty() -> true
            else -> false
        }
    }

    // Parse swiper slides from main pages
    private fun parseSwiperSlide(element: Element): SearchResponse? {
        // Get link
        val link = element.selectFirst("a") ?: return null
        val href = link.attr("href").takeIf { it.isNotBlank() } ?: return null
        val fixedHref = fixUrl(href)
        
        // Get title - try multiple selectors
        val title = when {
            element.selectFirst("span") != null -> element.selectFirst("span")?.text()?.trim()
            element.selectFirst("p") != null -> element.selectFirst("p")?.text()?.trim()
            element.selectFirst(".caption span:first-child") != null -> 
                element.selectFirst(".caption span:first-child")?.text()?.trim()
            element.selectFirst("b") != null -> element.selectFirst("b")?.text()?.trim()
            else -> null
        } ?: return null
        
        // Get thumbnail
        val img = element.selectFirst("img")
        val posterUrl = img?.attr("src")?.let { 
            if (it.startsWith("http")) it else "$mainUrl/$it".removePrefix("$mainUrl//")
        }
        
        // Check if it's a TV series (contains /videos/ in URL)
        val isTvSeries = fixedHref.contains("/videos/") && fixedHref.split("/").size > 6
        
        return if (isTvSeries) {
            newTvSeriesSearchResponse(title, fixedHref) {
                this.posterUrl = posterUrl
            }
        } else {
            newAnimeSearchResponse(title, fixedHref) {
                this.posterUrl = posterUrl
            }
        }
    }

    // Parse BINGE! items (TV series)
    private fun parseBingeItem(element: Element): SearchResponse? {
        // Get link
        val link = element.selectFirst("a") ?: return null
        val href = link.attr("href").takeIf { it.isNotBlank() } ?: return null
        val fixedHref = fixUrl(href)
        
        // Get title from paragraph
        val title = element.selectFirst("p")?.text()?.trim() ?: return null
        
        // Get thumbnail
        val img = element.selectFirst("img")
        val posterUrl = img?.attr("src")?.let { 
            if (it.startsWith("http")) it else "$mainUrl/$it".removePrefix("$mainUrl//")
        }
        
        // BINGE! items are TV series
        return newTvSeriesSearchResponse(title, fixedHref) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val document = app.post(
                "$mainUrl/player/?recherche",
                data = mapOf("recherche" to query)
            ).document
            
            // Search results use the .zone structure
            document.select("div[style*='margin-bottom:20px']").mapNotNull { element ->
                parseSearchResult(element)
            }.distinctBy { it.url }
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    // Parse search results (different structure!)
    private fun parseSearchResult(element: Element): SearchResponse? {
        // Get link
        val link = element.selectFirst("a") ?: return null
        val href = link.attr("href").takeIf { it.isNotBlank() } ?: return null
        val fixedHref = fixUrl(href)
        
        // Get title from: <span class="txtblanc14"><a><b>TITLE</b></a></span>
        val titleElement = element.selectFirst(".txtblanc14 a b, .lienblancrouge14 b, b")
        val title = titleElement?.text()?.trim() ?: return null
        
        // Get thumbnail from: <div class="gauche"><a><img src="..."></a></div>
        val img = element.selectFirst("img")
        val posterUrl = img?.attr("src")?.let { 
            if (it.startsWith("http")) it else "$mainUrl/$it".removePrefix("$mainUrl//")
        }
        
        // Check if it's a TV series
        val isTvSeries = fixedHref.contains("/videos/") && fixedHref.split("/").size > 6
        
        return if (isTvSeries) {
            newTvSeriesSearchResponse(title, fixedHref) {
                this.posterUrl = posterUrl
            }
        } else {
            newAnimeSearchResponse(title, fixedHref) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        return try {
            val document = app.get(url).document
            
            // Check if it's a BINGE! TV series page
            val isTvSeries = url.contains("/videos/") && url.split("/").size > 6
            
            if (isTvSeries) {
                // Parse TV series
                return parseTvSeries(url, document)
            } else {
                // Parse movie/single video
                return parseMovie(url, document)
            }
            
        } catch (e: Exception) {
            newMovieLoadResponse("Error", url, TvType.Movie, url) {
                this.plot = "Failed to load: ${e.message}"
            }
        }
    }
    
    private suspend fun parseMovie(url: String, document: org.jsoup.nodes.Document): LoadResponse {
        // Try different selectors for title
        val title = document.selectFirst("h1, .title, .txtblanc14 b, .zonetitre .divorangegrand")?.text()?.trim() 
            ?: "Unknown Title"
        
        // Try different selectors for poster
        val poster = document.selectFirst("img[src*='vignettes'], img[src*='head'], video[poster]")?.attr("src")?.let {
            if (it.startsWith("http")) it else "$mainUrl/$it".removePrefix("$mainUrl//")
        } ?: document.selectFirst("video")?.attr("poster")?.let {
            if (it.startsWith("http")) it else "$mainUrl/$it".removePrefix("$mainUrl//")
        }
        
        val plot = document.selectFirst(".description, .plot, p")?.text()?.trim()
        
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = plot
        }
    }
    
    private suspend fun parseTvSeries(url: String, document: org.jsoup.nodes.Document): LoadResponse {
        // Extract series title from URL or page
        val urlParts = url.split("/")
        val seriesName = urlParts.getOrNull(urlParts.size - 2) ?: "Unknown Series"
        val cleanName = seriesName.replace("_", " ").replace("-", " ").trim()
        
        val title = document.selectFirst("h1, .title, .txtblanc14 b")?.text()?.trim() 
            ?: cleanName.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        
        // Get poster
        val poster = document.selectFirst("img[src*='vignettes']")?.attr("src")?.let {
            if (it.startsWith("http")) it else "$mainUrl/$it".removePrefix("$mainUrl//")
        }
        
        val plot = document.selectFirst(".description, .plot, p")?.text()?.trim()
        
        // Get episodes (for BINGE! series, episodes are in /videos/.../1, /videos/.../2, etc.)
        val episodes = mutableListOf<Episode>()
        
        // Try to find episode links
        val episodeLinks = document.select("a[href*='/videos/']")
        var episodeNum = 1
        for (link in episodeLinks) {
            val href = link.attr("href").takeIf { it.isNotBlank() }
            if (href != null && href.contains("/videos/") && href.matches(Regex(".*/\\d+\$"))) {
                val episodeUrl = fixUrl(href)
                val episodeTitle = link.text().trim().takeIf { it.isNotBlank() } ?: "Episode $episodeNum"
                
                episodes.add(
                    newEpisode(episodeUrl) {
                        this.name = episodeTitle
                        this.episode = episodeNum
                        this.season = 1
                    }
                )
                episodeNum++
            }
        }
        
        // If no episodes found, at least add the current page as episode 1
        if (episodes.isEmpty() && url.contains("/videos/")) {
            episodes.add(
                newEpisode(url) {
                    this.name = "Episode 1"
                    this.episode = 1
                    this.season = 1
                }
            )
        }
        
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    // FIXED loadLinks function - uses VimeoExtractor properly
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            if (data.startsWith("http")) {
                val document = app.get(data).document
                
                // Look for Vimeo iframe
                val iframe = document.selectFirst("iframe[src*='vimeo.com']")
                if (iframe != null) {
                    val iframeSrc = iframe.attr("src").takeIf { it.isNotBlank() }
                        ?.let { if (it.startsWith("http")) it else "https:$it" }
                    
                    if (iframeSrc != null) {
                        // Extract Vimeo video ID from iframe URL
                        val videoId = iframeSrc.substringAfterLast("/").substringBefore("?")
                        
                        // Use our VimeoExtractor
                        val extractor = VimeoExtractor()
                        extractor.getUrl(
                            url = "https://vimeo.com/$videoId",
                            referer = data,
                            subtitleCallback = subtitleCallback,
                            callback = callback
                        )
                        return true
                    }
                }
                
                // Look for YouTube iframe (keep existing YouTube support)
                val youtubeIframe = document.selectFirst("iframe[src*='youtube.com'], iframe[src*='youtu.be']")
                if (youtubeIframe != null) {
                    val youtubeSrc = youtubeIframe.attr("src").takeIf { it.isNotBlank() }
                        ?.let { if (it.startsWith("http")) it else "https:$it" }
                    
                    if (youtubeSrc != null) {
                        // Extract YouTube video ID
                        val youtubePattern = Regex("""(?:youtube\.com/embed/|youtu\.be/|youtube\.com/watch\?v=)([A-Za-z0-9_-]{11})""")
                        val youtubeMatch = youtubePattern.find(youtubeSrc)
                        if (youtubeMatch != null) {
                            val videoId = youtubeMatch.groupValues[1]
                            val youtubeUrl = "https://www.youtube.com/watch?v=$videoId"
                            
                            if (loadExtractor(youtubeUrl, subtitleCallback, callback)) {
                                return true
                            }
                        }
                    }
                }
                
                // Fallback: Look for direct video element
                val video = document.selectFirst("video source[src], video[src]")
                if (video != null) {
                    val videoSrc = video.attr("src").takeIf { it.isNotBlank() }
                        ?.let { src ->
                            when {
                                src.startsWith("http") -> src
                                src.startsWith("//") -> "https:$src"
                                src.startsWith("/") -> "$mainUrl$src"
                                else -> "$mainUrl/$src"
                            }
                        }
                    
                    if (videoSrc != null) {
                        val quality = determineQualityFromUrl(videoSrc)
                        callback.invoke(
                            newExtractorLink(
                                source = name,
                                name = "Direct Video",
                                url = videoSrc
                            ) {
                                this.referer = mainUrl
                                this.quality = quality
                            }
                        )
                        return true
                    }
                }
            }
            
            false
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
    
    private fun determineQualityFromUrl(url: String): Int {
        val urlLower = url.lowercase()
        
        return when {
            urlLower.contains("4k") || urlLower.contains("2160") -> Qualities.P2160.value
            urlLower.contains("1440") || urlLower.contains("2k") -> Qualities.P1440.value
            urlLower.contains("1080") || urlLower.contains("fullhd") -> Qualities.P1080.value
            urlLower.contains("720") || urlLower.contains("hd") -> Qualities.P720.value
            urlLower.contains("480") || urlLower.contains("sd") -> Qualities.P480.value
            urlLower.contains("360") -> Qualities.P360.value
            urlLower.contains("240") -> Qualities.P240.value
            else -> Qualities.Unknown.value
        }
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
