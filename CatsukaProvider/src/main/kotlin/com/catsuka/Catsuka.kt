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

    // Dynamically generate main page from categories
    override val mainPage = mainPageOf(
        categoriesUrl to "Categories",
        "$playerUrl/?recherche=&sort=date" to "Latest",
        "$playerUrl/?recherche=&sort=views" to "Most Viewed",
        "$playerUrl/?recherche=&sort=random" to "Random"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        return when {
            request.data == categoriesUrl -> {
                // Handle categories page
                getCategoriesPage()
            }
            request.data.contains("/?recherche=") -> {
                // Handle sorted lists (latest, most viewed, random)
                getSortedPage(request.data, page, request.name)
            }
            else -> newHomePageResponse(request.name, emptyList())
        }
    }

    private suspend fun getCategoriesPage(): HomePageResponse {
        val document = app.get(categoriesUrl).document
        
        val categorySections = document.select(".player-slider")
        val items = mutableListOf<HomePageList>()
        
        categorySections.forEach { section ->
            val categoryName = section.selectFirst(".divorangegrand b")?.text() ?: return@forEach
            val seeAllLink = section.selectFirst("a[href*='player/categorie/']")?.attr("href")
            
            if (seeAllLink != null) {
                // Extract category items from the swiper
                val categoryItems = section.select(".swiper-slide").mapNotNull { slide ->
                    slide.toSearchResponse()
                }
                
                if (categoryItems.isNotEmpty()) {
                    items.add(HomePageList(categoryName, categoryItems))
                }
            }
        }
        
        return newHomePageResponse(items)
    }

    private suspend fun getSortedPage(url: String, page: Int, name: String): HomePageResponse {
        val pageUrl = if (page > 1) "$url&page=$page" else url
        val document = app.get(pageUrl).document
        
        // Look for video items in sorted lists
        val items = document.select("a[href*='/player/']").mapNotNull { element ->
            element.toSearchResponse()
        }.distinctBy { it.url }
        
        return newHomePageResponse(name, items, hasNext = items.isNotEmpty())
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        // Check if it's a video link with thumbnail
        val link = this.selectFirst("a[href]") ?: return null
        val href = link.attr("href")
        
        if (href.isBlank() || !href.contains("/player/")) return null
        
        val fullUrl = if (href.startsWith("http")) href else "$mainUrl$href"
        
        // Get title from span or img alt
        val title = this.selectFirst("span")?.text()?.trim() 
            ?: link.selectFirst("img")?.attr("alt")?.trim()
            ?: return null
        
        // Get thumbnail
        val thumbnail = link.selectFirst("img")?.attr("src")?.let { 
            if (it.startsWith("http")) it else "$mainUrl$it"
        }
        
        // Parse category info from parent sections
        val category = findCategoryFromParent(this)
        
        val tvType = when {
            title.contains("Movie", ignoreCase = true) -> TvType.AnimeMovie
            title.contains("Episode", ignoreCase = true) || 
            title.contains("Season", ignoreCase = true) -> TvType.Anime
            else -> TvType.OVA
        }
        
        return newMovieSearchResponse(title, fullUrl, tvType) {
            this.posterUrl = thumbnail ?: getDefaultPoster(tvType)
            this.description = category?.let { "Category: $it" }
        }
    }

    private fun findCategoryFromParent(element: Element): String? {
        var current = element.parent()
        while (current != null) {
            val categoryDiv = current.selectFirst(".divorangegrand b")
            if (categoryDiv != null) {
                return categoryDiv.text()
            }
            current = current.parent()
        }
        return null
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$playerUrl/?recherche=${URLEncoder.encode(query, "UTF-8")}"
        val document = app.get(searchUrl).document
        
        return document.select("a[href*='/player/']").mapNotNull { element ->
            val title = element.text().trim()
            if (title.contains(query, ignoreCase = true) || query.isBlank()) {
                element.toSearchResponse()
            } else null
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        
        // Extract title from multiple possible locations
        val title = document.selectFirst("meta[property='og:title']")?.attr("content")
            ?: document.selectFirst("h1, h2, .title")?.text()?.trim()
            ?: "Catsuka Animation"
        
        // Extract description
        val description = document.selectFirst("meta[property='og:description']")?.attr("content")
            ?: document.selectFirst("meta[name=description]")?.attr("content")
            ?: "Animation work from Catsuka Player"
        
        // Extract thumbnail
        val thumbnail = document.selectFirst("meta[property='og:image']")?.attr("content")
            ?: document.selectFirst("img")?.attr("src")?.let {
                if (it.startsWith("http")) it else "$mainUrl$it"
            }
        
        // Try to find video embed
        val videoElement = document.selectFirst("video, iframe[src*='youtube.com'], iframe[src*='vimeo.com']")
        val isMovie = title.contains("Movie", ignoreCase = true) || 
                     url.contains("movie", ignoreCase = true)
        
        if (isMovie || videoElement != null) {
            // Treat as movie/single video
            return newMovieLoadResponse(title, url, TvType.AnimeMovie, url) {
                this.posterUrl = thumbnail ?: getDefaultPoster(TvType.AnimeMovie)
                this.plot = description
            }
        } else {
            // Check if it's a series page with multiple videos
            val episodeLinks = document.select("a[href*='/player/']")
            val episodes = episodeLinks.mapIndexedNotNull { index, episodeLink ->
                val episodeUrl = episodeLink.attr("href").let {
                    if (it.startsWith("http")) it else "$mainUrl$it"
                }
                val episodeTitle = episodeLink.text().trim()
                
                if (episodeUrl != url && episodeTitle.isNotBlank()) {
                    newEpisode(episodeUrl) {
                        this.name = episodeTitle
                        this.episode = index + 1
                        this.season = 1
                    }
                } else null
            }
            
            if (episodes.isNotEmpty()) {
                return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
                    this.posterUrl = thumbnail ?: getDefaultPoster(TvType.Anime)
                    this.plot = description
                }
            } else {
                // Single video page
                return newMovieLoadResponse(title, url, TvType.AnimeMovie, url) {
                    this.posterUrl = thumbnail ?: getDefaultPoster(TvType.AnimeMovie)
                    this.plot = description
                }
            }
        }
    }

    // Helper function to get all category URLs
    private suspend fun getAllCategoryUrls(): List<Pair<String, String>> {
        val document = app.get(categoriesUrl).document
        val categories = mutableListOf<Pair<String, String>>()
        
        document.select(".player-slider").forEach { section ->
            val categoryName = section.selectFirst(".divorangegrand b")?.text()
            val seeAllLink = section.selectFirst("a[href*='player/categorie/']")?.attr("href")
            
            if (categoryName != null && seeAllLink != null) {
                val fullUrl = if (seeAllLink.startsWith("http")) seeAllLink else "$mainUrl$seeAllLink"
                categories.add(Pair(categoryName, fullUrl))
            }
        }
        
        return categories
    }

    private fun getDefaultPoster(type: TvType): String? {
        return when (type) {
            TvType.AnimeMovie -> "https://via.placeholder.com/300x450/FF6B6B/FFFFFF?text=Anime+Movie"
            TvType.Anime -> "https://via.placeholder.com/300x450/4ECDC4/FFFFFF?text=Anime+Series"
            TvType.OVA -> "https://via.placeholder.com/300x450/45B7D1/FFFFFF?text=OVA"
            else -> null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        var foundLinks = false
        
        // Method 1: Look for video elements
        document.select("video source[src], video[src]").forEach { source ->
            val videoUrl = source.attr("src").ifBlank { source.attr("data-src") }
            if (videoUrl.isNotBlank()) {
                foundLinks = true
                callback(
                    newExtractorLink(
                        source = name,
                        name = "Direct Video",
                        url = fixUrl(videoUrl)
                    ) {
                        this.referer = data
                        this.quality = Qualities.Unknown.value
                        this.type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 
                                   else ExtractorLinkType.VIDEO
                    }
                )
            }
        }
        
        // Method 2: Look for YouTube/Vimeo iframes (common on Catsuka)
        document.select("iframe[src*='youtube.com'], iframe[src*='youtu.be'], iframe[src*='vimeo.com']").forEach { iframe ->
            val iframeSrc = iframe.attr("src")
            if (iframeSrc.isNotBlank()) {
                foundLinks = true
                loadExtractor(fixUrl(iframeSrc), data, subtitleCallback, callback)
            }
        }
        
        // Method 3: Look for video.js player (Catsuka uses Video.js)
        document.select("video[data-setup], [data-video-id]").forEach { video ->
            val videoId = video.attr("data-video-id").ifBlank { video.attr("id") }
            if (videoId.isNotBlank()) {
                // Catsuka might use YouTube or Vimeo embeds
                val possibleUrls = listOf(
                    "https://www.youtube.com/watch?v=$videoId",
                    "https://vimeo.com/$videoId",
                    "https://player.vimeo.com/video/$videoId"
                )
                
                possibleUrls.forEach { url ->
                    loadExtractor(url, data, subtitleCallback, callback)
                }
            }
        }
        
        // Method 4: Check for script with video data
        if (!foundLinks) {
            document.select("script").forEach { script ->
                val scriptText = script.html()
                // Look for YouTube/Vimeo URLs in scripts
                val patterns = listOf(
                    Regex("""(https?://(?:www\.)?youtube\.com/watch\?v=[^\s"']+)"""),
                    Regex("""(https?://(?:www\.)?youtu\.be/[^\s"']+)"""),
                    Regex("""(https?://(?:www\.)?vimeo\.com/[^\s"']+)"""),
                    Regex("""src\s*:\s*['"](https?://[^"']+)['"]""")
                )
                
                patterns.forEach { pattern ->
                    pattern.findAll(scriptText).forEach { match ->
                        val videoUrl = match.groupValues[1]
                        if (videoUrl.contains("youtube") || videoUrl.contains("vimeo") || 
                            videoUrl.contains("youtu.be")) {
                            foundLinks = true
                            loadExtractor(fixUrl(videoUrl), data, subtitleCallback, callback)
                        }
                    }
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
            else -> url
        }
    }
}
