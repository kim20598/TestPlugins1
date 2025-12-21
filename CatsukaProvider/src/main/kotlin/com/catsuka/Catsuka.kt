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
                url.contains("/binge/") -> {
                    document.select(".swiper-slide").mapNotNull { element ->
                        parseBingeItem(element)
                    }
                }
                url.contains("/categorie/") -> {
                    parseCategoryPage(document)
                }
                url.contains("/highlight/") || url.contains("/highlights") -> {
                    parseHighlightsPage(document)
                }
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
    
    private fun parseCategoryPage(document: org.jsoup.nodes.Document): List<SearchResponse> {
        val items = mutableListOf<SearchResponse>()
        
        document.select("#tableau li").forEach { element ->
            parseTableauItem(element)?.let { items.add(it) }
        }
        
        if (items.isEmpty()) {
            document.select(".swiper-slide").forEach { element ->
                parseSwiperSlide(element)?.let { items.add(it) }
            }
        }
        
        return items.distinctBy { it.url }
    }
    
    private fun parseTableauItem(element: Element): SearchResponse? {
        val imgLink = element.selectFirst("a") ?: return null
        val href = imgLink.attr("href").takeIf { it.isNotBlank() } ?: return null
        val fixedHref = fixUrl(href)
        
        val titleElement = element.selectFirst("span a b")
        val title = titleElement?.text()?.trim() ?: return null
        
        val img = element.selectFirst("img")
        val posterUrl = img?.attr("src")?.let { 
            if (it.startsWith("http")) it else "$mainUrl/$it".removePrefix("$mainUrl//")
        }
        
        return newAnimeSearchResponse(title, fixedHref) {
            this.posterUrl = posterUrl
        }
    }
    
    private fun parseHighlightsPage(document: org.jsoup.nodes.Document): List<SearchResponse> {
        val items = mutableListOf<SearchResponse>()
        
        document.select(".swiper-slide, .item.video, #tableau li").forEach { element ->
            parseSwiperSlide(element)?.let { items.add(it) }
            parseTableauItem(element)?.let { items.add(it) }
        }
        
        return items.distinctBy { it.url }
    }
    
    private fun parseMainOrUpdatesPage(document: org.jsoup.nodes.Document): List<SearchResponse> {
        val items = mutableListOf<SearchResponse>()
        
        document.select(".swiper-slide, .item.video, #tableau li").forEach { element ->
            parseSwiperSlide(element)?.let { items.add(it) }
            parseTableauItem(element)?.let { items.add(it) }
        }
        
        return items.distinctBy { it.url }
    }
    
    private fun hasNextPage(document: org.jsoup.nodes.Document, currentPage: Int): Boolean {
        val pagination = document.select(".txtpagination, .pagination").text()
        
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

    private fun parseSwiperSlide(element: Element): SearchResponse? {
        val link = element.selectFirst("a") ?: return null
        val href = link.attr("href").takeIf { it.isNotBlank() } ?: return null
        val fixedHref = fixUrl(href)
        
        val title = when {
            element.selectFirst("span") != null -> element.selectFirst("span")?.text()?.trim()
            element.selectFirst("p") != null -> element.selectFirst("p")?.text()?.trim()
            element.selectFirst(".caption span:first-child") != null -> 
                element.selectFirst(".caption span:first-child")?.text()?.trim()
            element.selectFirst("b") != null -> element.selectFirst("b")?.text()?.trim()
            else -> null
        } ?: return null
        
        val img = element.selectFirst("img")
        val posterUrl = img?.attr("src")?.let { 
            if (it.startsWith("http")) it else "$mainUrl/$it".removePrefix("$mainUrl//")
        }
        
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

    private fun parseBingeItem(element: Element): SearchResponse? {
        val link = element.selectFirst("a") ?: return null
        val href = link.attr("href").takeIf { it.isNotBlank() } ?: return null
        val fixedHref = fixUrl(href)
        
        val title = element.selectFirst("p")?.text()?.trim() ?: return null
        
        val img = element.selectFirst("img")
        val posterUrl = img?.attr("src")?.let { 
            if (it.startsWith("http")) it else "$mainUrl/$it".removePrefix("$mainUrl//")
        }
        
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
            
            document.select("div[style*='margin-bottom:20px']").mapNotNull { element ->
                parseSearchResult(element)
            }.distinctBy { it.url }
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    private fun parseSearchResult(element: Element): SearchResponse? {
        val link = element.selectFirst("a") ?: return null
        val href = link.attr("href").takeIf { it.isNotBlank() } ?: return null
        val fixedHref = fixUrl(href)
        
        val titleElement = element.selectFirst(".txtblanc14 a b, .lienblancrouge14 b, b")
        val title = titleElement?.text()?.trim() ?: return null
        
        val img = element.selectFirst("img")
        val posterUrl = img?.attr("src")?.let { 
            if (it.startsWith("http")) it else "$mainUrl/$it".removePrefix("$mainUrl//")
        }
        
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
            
            val isTvSeries = url.contains("/videos/") && url.split("/").size > 6
            
            if (isTvSeries) {
                parseTvSeries(url, document)
            } else {
                parseMovie(url, document)
            }
            
        } catch (e: Exception) {
            newMovieLoadResponse("Error", url, TvType.Movie, url) {
                this.plot = "Failed to load: ${e.message}"
            }
        }
    }
    
    private suspend fun parseMovie(url: String, document: org.jsoup.nodes.Document): LoadResponse {
        val title = document.selectFirst("h1, .title, .txtblanc14 b, .zonetitre .divorangegrand")?.text()?.trim() 
            ?: "Unknown Title"
        
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
        val urlParts = url.split("/")
        val seriesName = urlParts.getOrNull(urlParts.size - 2) ?: "Unknown Series"
        val cleanName = seriesName.replace("_", " ").replace("-", " ").trim()
        
        val title = document.selectFirst("h1, .title, .txtblanc14 b")?.text()?.trim() 
            ?: cleanName.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        
        val poster = document.selectFirst("img[src*='vignettes']")?.attr("src")?.let {
            if (it.startsWith("http")) it else "$mainUrl/$it".removePrefix("$mainUrl//")
        }
        
        val plot = document.selectFirst(".description, .plot, p")?.text()?.trim()
        
        val episodes = mutableListOf<Episode>()
        
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

    // FIXED loadLinks function - Using lambda configuration correctly
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val document = app.get(data).document
            
            // 1. Try Vimeo iframe first (most common)
            val vimeoIframe = document.selectFirst("iframe[src*='player.vimeo.com']")
            if (vimeoIframe != null) {
                val iframeSrc = vimeoIframe.attr("src")
                if (iframeSrc.isNotBlank()) {
                    val videoId = iframeSrc.substringAfter("video/").substringBefore("?").substringBefore("#")
                    if (videoId.isNotBlank()) {
                        loadExtractor("https://vimeo.com/$videoId", subtitleCallback, callback)
                        return true
                    }
                }
            }
            
            // 2. Try YouTube iframe
            val youtubeIframe = document.selectFirst("iframe[src*='youtube.com'], iframe[src*='youtu.be']")
            if (youtubeIframe != null) {
                val youtubeSrc = youtubeIframe.attr("src")
                if (youtubeSrc.isNotBlank()) {
                    val youtubeId = when {
                        youtubeSrc.contains("youtube.com/embed/") -> 
                            youtubeSrc.substringAfter("youtube.com/embed/").substringBefore("?").substringBefore("#")
                        youtubeSrc.contains("youtu.be/") -> 
                            youtubeSrc.substringAfter("youtu.be/").substringBefore("?").substringBefore("#")
                        else -> null
                    }
                    
                    if (youtubeId != null) {
                        loadExtractor("https://www.youtube.com/watch?v=$youtubeId", subtitleCallback, callback)
                        return true
                    }
                }
            }
            
            // 3. Try direct video source
            val videoSource = document.selectFirst("video source[src], video[src]")
            if (videoSource != null) {
                val videoSrc = videoSource.attr("src")
                if (videoSrc.isNotBlank()) {
                    val fullUrl = when {
                        videoSrc.startsWith("http") -> videoSrc
                        videoSrc.startsWith("//") -> "https:$videoSrc"
                        videoSrc.startsWith("/") -> "$mainUrl$videoSrc"
                        else -> "$mainUrl/$videoSrc"
                    }
                    
                    val quality = determineQualityFromUrl(fullUrl)
                    val isM3u8 = videoSrc.contains(".m3u8")
                    
                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = "Direct Video",
                            url = fullUrl
                        ) {
                            this.quality = quality
                            this.isM3u8 = isM3u8
                        }
                    )
                    return true
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
