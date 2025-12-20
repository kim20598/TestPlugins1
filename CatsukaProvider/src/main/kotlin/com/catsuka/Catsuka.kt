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
        "$mainUrl/player/categorie/episode" to "Episodes",
        "$mainUrl/player/categorie/opening" to "Openings",
        "$mainUrl/player/categorie/trailer" to "Trailers",
        "$mainUrl/player/categorie/extrait" to "Excerpts"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        return try {
            val url = request.data + if (page > 1) "?page=$page" else ""
            val document = app.get(url).document
            
            val items = when {
                // BINGE! page (TV series) - special handling
                url.contains("/binge/") -> {
                    document.select(".swiper-slide").mapNotNull { element ->
                        parseBingeItem(element)
                    }
                }
                // All other pages (Highlights, Categories, Updates, etc.)
                else -> {
                    // Try ALL possible selectors to catch different page structures
                    parseAllVideoElements(document)
                }
            }
            
            newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
        } catch (e: Exception) {
            e.printStackTrace()
            newHomePageResponse(request.name, emptyList())
        }
    }
    
    // NEW: Parse all possible video elements from any page
    private fun parseAllVideoElements(document: org.jsoup.nodes.Document): List<SearchResponse> {
        val items = mutableListOf<SearchResponse>()
        
        // 1. Try .swiper-slide elements (most common)
        document.select(".swiper-slide").forEach { element ->
            parseSwiperSlide(element)?.let { items.add(it) }
        }
        
        // 2. Try .item.video elements
        document.select(".item.video").forEach { element ->
            parseSwiperSlide(element)?.let { items.add(it) }
        }
        
        // 3. Try search result divs
        document.select("div[style*='margin-bottom:20px']").forEach { element ->
            parseSearchResult(element)?.let { items.add(it) }
        }
        
        // 4. Try any image with link that looks like a video
        document.select("a img").forEach { img ->
            val link = img.parent()?.tagName()?.takeIf { it == "a" }?.let { img.parent() } ?: return@forEach
            val href = link.attr("href").takeIf { it.isNotBlank() } ?: return@forEach
            
            // Check if this looks like a video link (not just any link)
            if (href.contains("/player/") || href.contains("/highlight/") || 
                href.contains("/categorie/") || href.contains("/videos/")) {
                
                val fixedHref = fixUrl(href)
                val title = img.attr("alt").takeIf { it.isNotBlank() } 
                    ?: link.selectFirst("b, span, p")?.text()?.trim()
                    ?: return@forEach
                
                val posterUrl = img.attr("src").takeIf { it.isNotBlank() }?.let { 
                    if (it.startsWith("http")) it else "$mainUrl/$it".removePrefix("$mainUrl//")
                }
                
                // Check if it's a TV series (contains /videos/ in URL)
                val isTvSeries = fixedHref.contains("/videos/") && fixedHref.split("/").size > 6
                
                if (isTvSeries) {
                    items.add(
                        newTvSeriesSearchResponse(title, fixedHref) {
                            this.posterUrl = posterUrl
                        }
                    )
                } else {
                    items.add(
                        newAnimeSearchResponse(title, fixedHref) {
                            this.posterUrl = posterUrl
                        }
                    )
                }
            }
        }
        
        return items.distinctBy { it.url }
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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            if (data.startsWith("http")) {
                val document = app.get(data).document
                
                // Look for iframe first (Vimeo, YouTube embeds)
                val iframe = document.selectFirst("iframe[src]")
                if (iframe != null) {
                    val iframeSrc = iframe.attr("src").takeIf { it.isNotBlank() }
                        ?.let { if (it.startsWith("http")) it else "https:$it" }
                    
                    if (iframeSrc != null) {
                        // Check if it's Vimeo
                        if (iframeSrc.contains("vimeo.com")) {
                            val vimeoPattern = Regex("""vimeo\.com/(?:video/)?(\d+)""")
                            val vimeoMatch = vimeoPattern.find(iframeSrc)
                            if (vimeoMatch != null) {
                                val videoId = vimeoMatch.groupValues[1]
                                val vimeoUrl = "https://vimeo.com/$videoId"
                                
                                val extractor = VimeoExtractor()
                                extractor.getUrl(vimeoUrl, data, subtitleCallback, callback)
                                return true
                            }
                        }
                        
                        // Check if it's YouTube
                        if (iframeSrc.contains("youtube.com") || iframeSrc.contains("youtu.be")) {
                            val youtubePattern = Regex("""(?:youtube\.com/embed/|youtu\.be/|youtube\.com/watch\?v=)([A-Za-z0-9_-]{11})""")
                            val youtubeMatch = youtubePattern.find(iframeSrc)
                            if (youtubeMatch != null) {
                                val videoId = youtubeMatch.groupValues[1]
                                val youtubeUrl = "https://www.youtube.com/watch?v=$videoId"
                                
                                if (loadExtractor(youtubeUrl, subtitleCallback, callback)) {
                                    return true
                                }
                            }
                        }
                        
                        if (loadExtractor(iframeSrc, subtitleCallback, callback)) {
                            return true
                        }
                    }
                }
                
                // Look for direct video element
                val videoSources = document.select("video source[src]")
                for (video in videoSources) {
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
                        val type = video.attr("type").takeIf { it.isNotBlank() }
                        val format = when {
                            type?.contains("mp4") == true -> "MP4"
                            type?.contains("webm") == true -> "WebM"
                            videoSrc.contains(".mp4") -> "MP4"
                            videoSrc.contains(".webm") -> "WebM"
                            else -> "Video"
                        }
                        
                        val qualityName = when (quality) {
                            Qualities.P2160.value -> "4K"
                            Qualities.P1440.value -> "1440p"
                            Qualities.P1080.value -> "1080p"
                            Qualities.P720.value -> "720p"
                            Qualities.P480.value -> "480p"
                            Qualities.P360.value -> "360p"
                            Qualities.P240.value -> "240p"
                            else -> "Unknown"
                        }
                        
                        callback.invoke(
                            newExtractorLink(
                                source = name,
                                name = "$format - $qualityName",
                                url = videoSrc
                            ) {
                                this.referer = mainUrl
                                this.quality = quality
                            }
                        )
                        return true
                    }
                }
                
                // Look for video tag with direct src
                val videoTags = document.select("video[src]")
                for (videoTag in videoTags) {
                    val videoSrc = videoTag.attr("src").takeIf { it.isNotBlank() }
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
                        val qualityName = when (quality) {
                            Qualities.P2160.value -> "4K"
                            Qualities.P1440.value -> "1440p"
                            Qualities.P1080.value -> "1080p"
                            Qualities.P720.value -> "720p"
                            Qualities.P480.value -> "480p"
                            Qualities.P360.value -> "360p"
                            Qualities.P240.value -> "240p"
                            else -> "Unknown"
                        }
                        
                        callback.invoke(
                            newExtractorLink(
                                source = name,
                                name = "Direct Video - $qualityName",
                                url = videoSrc
                            ) {
                                this.referer = mainUrl
                                this.quality = quality
                            }
                        )
                        return true
                    }
                }
                
                // Look for Vimeo/YouTube in scripts
                val scripts = document.select("script")
                for (script in scripts) {
                    val scriptText = script.html()
                    
                    // Vimeo pattern
                    val vimeoPattern = Regex("""vimeo\.com/(?:video/)?(\d+)""")
                    val vimeoMatch = vimeoPattern.find(scriptText)
                    if (vimeoMatch != null) {
                        val videoId = vimeoMatch.groupValues[1]
                        val vimeoUrl = "https://vimeo.com/$videoId"
                        
                        val extractor = VimeoExtractor()
                        extractor.getUrl(vimeoUrl, data, subtitleCallback, callback)
                        return true
                    }
                    
                    // YouTube pattern
                    val youtubePattern = Regex("""youtube\.com/embed/([A-Za-z0-9_-]{11})""")
                    val youtubeMatch = youtubePattern.find(scriptText)
                    if (youtubeMatch != null) {
                        val videoId = youtubeMatch.groupValues[1]
                        val youtubeUrl = "https://www.youtube.com/watch?v=$videoId"
                        
                        if (loadExtractor(youtubeUrl, subtitleCallback, callback)) {
                            return true
                        }
                    }
                }
                
                // Check for video links
                val videoLinks = document.select("a[href*='.mp4'], a[href*='.webm'], a[href*='.m3u8']")
                for (link in videoLinks) {
                    val href = link.attr("href").takeIf { it.isNotBlank() }
                    if (href != null && (href.contains(".mp4") || href.contains(".webm") || href.contains(".m3u8"))) {
                        val videoSrc = when {
                            href.startsWith("http") -> href
                            href.startsWith("//") -> "https:$href"
                            href.startsWith("/") -> "$mainUrl$href"
                            else -> "$mainUrl/$href"
                        }
                        
                        val quality = determineQualityFromUrl(videoSrc)
                        val qualityName = when (quality) {
                            Qualities.P2160.value -> "4K"
                            Qualities.P1440.value -> "1440p"
                            Qualities.P1080.value -> "1080p"
                            Qualities.P720.value -> "720p"
                            Qualities.P480.value -> "480p"
                            Qualities.P360.value -> "360p"
                            Qualities.P240.value -> "240p"
                            else -> "Unknown"
                        }
                        
                        if (videoSrc.contains(".m3u8")) {
                            M3u8Helper.generateM3u8(
                                source = name,
                                streamUrl = videoSrc,
                                referer = mainUrl,
                                quality = quality
                            ).forEach(callback)
                        } else {
                            callback.invoke(
                                newExtractorLink(
                                    source = name,
                                    name = "Direct Video - $qualityName",
                                    url = videoSrc
                                ) {
                                    this.referer = mainUrl
                                    this.quality = quality
                                }
                            )
                        }
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
