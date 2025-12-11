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
    private val highlightsUrl = "$mainUrl/player/highlights/"
    private val updatesUrl = "$mainUrl/player/updates/"
    private val bingeUrl = "$mainUrl/player/binge/"

    // Main page sections
    override val mainPage = mainPageOf(
        playerUrl to "Catsuka Home",
        categoriesUrl to "Categories",
        highlightsUrl to "Highlights",
        updatesUrl to "Latest Updates",
        bingeUrl to "Binge! Anime Series",
        "$playerUrl/?recherche=&sort=date" to "Latest Videos",
        "$playerUrl/?recherche=&sort=views" to "Most Viewed",
        "$playerUrl/?recherche=&sort=random" to "Random Videos"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        return when {
            request.data == playerUrl -> getHomePage()
            request.data == categoriesUrl -> getCategoriesPage()
            request.data == highlightsUrl -> getHighlightsPage()
            request.data == updatesUrl -> getUpdatesPage(page)
            request.data == bingeUrl -> getBingePage()
            request.data.contains("/?recherche=") -> getSortedPage(request.data, page, request.name)
            else -> newHomePageResponse(request.name, emptyList())
        }
    }

    private suspend fun getHomePage(): HomePageResponse {
        val document = app.get(playerUrl).document
        val items = mutableListOf<HomePageList>()
        
        // 1. Main slider videos (header section)
        val sliderItems = document.select(".main-slider .item.video").mapNotNull { sliderItem ->
            val link = sliderItem.selectFirst("a[href*='/player/']")
            val title = sliderItem.selectFirst(".caption span:first-child")?.text()
            val description = sliderItem.selectFirst(".caption span:nth-child(2)")?.text()
            
            if (link != null && title != null) {
                val href = link.attr("href")
                val fullUrl = if (href.startsWith("http")) href else "$mainUrl$href"
                
                // Get poster from video element
                val poster = sliderItem.selectFirst("video")?.attr("poster")?.let {
                    if (it.startsWith("http")) it else "$mainUrl$it"
                }
                
                newMovieSearchResponse(title, fullUrl, TvType.AnimeMovie) {
                    this.posterUrl = poster
                    this.description = description
                }
            } else null
        }
        
        if (sliderItems.isNotEmpty()) {
            items.add(HomePageList("Featured Videos", sliderItems))
        }
        
        // 2. Highlights section
        val highlights = document.select(".player-slider:first-child .swiper-slide a[href*='/player/highlight/']")
            .mapNotNull { highlight ->
                val href = highlight.attr("href")
                val fullUrl = if (href.startsWith("http")) href else "$mainUrl$href"
                val title = highlight.parent()?.selectFirst("span")?.text()
                    ?: highlight.selectFirst("img")?.attr("alt")?.trim()
                
                if (title != null) {
                    val thumbnail = highlight.selectFirst("img")?.attr("src")?.let {
                        if (it.startsWith("http")) it else "$mainUrl$it"
                    }
                    
                    newMovieSearchResponse(title, fullUrl, TvType.Anime) {
                        this.posterUrl = thumbnail
                        this.description = "Animator/Studio Highlight"
                    }
                } else null
            }
        
        if (highlights.isNotEmpty()) {
            items.add(HomePageList("Animator Highlights", highlights))
        }
        
        // 3. New entries section
        val newEntries = document.select(".player-slider:nth-child(2) .swiper-slide").mapNotNull { slide ->
            slide.toSearchResponse()
        }
        
        if (newEntries.isNotEmpty()) {
            items.add(HomePageList("New Entries", newEntries))
        }
        
        // 4. Binge! section (Anime series)
        val bingeItems = document.select(".player-slider:nth-child(3) .swiper-slide").mapNotNull { slide ->
            slide.toSearchResponse()
        }
        
        if (bingeItems.isNotEmpty()) {
            items.add(HomePageList("Binge! Anime Series", bingeItems))
        }
        
        // 5. Special tags section
        val specialTags = document.select(".player-slider:nth-child(4) .swiper-slide a[href*='/player/highlight/']")
            .mapNotNull { tag ->
                val href = tag.attr("href")
                val fullUrl = if (href.startsWith("http")) href else "$mainUrl$href"
                val title = tag.selectFirst("img")?.attr("alt")?.trim()
                    ?: href.substringAfter("highlight/").replace("_", " ").capitalize()
                
                val thumbnail = tag.selectFirst("img")?.attr("src")?.let {
                    if (it.startsWith("http")) it else "$mainUrl$it"
                }
                
                newMovieSearchResponse(title, fullUrl, TvType.Anime) {
                    this.posterUrl = thumbnail
                    this.description = "Special Tag Collection"
                }
            }
        
        if (specialTags.isNotEmpty()) {
            items.add(HomePageList("Special Tags", specialTags))
        }
        
        // 6. Category quick links sections
        val categorySections = document.select(".player-slider").drop(4) // Skip first 4 sections we already processed
        
        categorySections.forEachIndexed { index, section ->
            val categoryLinks = section.select(".divorangegrand a[href*='player/categorie/']")
            if (categoryLinks.isNotEmpty()) {
                val categoryName = categoryLinks.firstOrNull()?.text() ?: "Category ${index + 1}"
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

    private suspend fun getCategoriesPage(): HomePageResponse {
        val document = app.get(categoriesUrl).document
        
        val categorySections = document.select(".player-slider")
        val items = mutableListOf<HomePageList>()
        
        categorySections.forEach { section ->
            val categoryName = section.selectFirst(".divorangegrand b")?.text() ?: return@forEach
            val seeAllLink = section.selectFirst("a[href*='player/categorie/']")?.attr("href")
            
            if (seeAllLink != null) {
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

    private suspend fun getHighlightsPage(): HomePageResponse {
        val document = app.get(highlightsUrl).document
        
        // Look for highlight items
        val items = document.select("a[href*='/player/highlight/']").mapNotNull { highlight ->
            val href = highlight.attr("href")
            val fullUrl = if (href.startsWith("http")) href else "$mainUrl$href"
            val title = highlight.selectFirst("img")?.attr("alt")?.trim()
                ?: href.substringAfter("highlight/").replace("_", " ").capitalize()
            
            val thumbnail = highlight.selectFirst("img")?.attr("src")?.let {
                if (it.startsWith("http")) it else "$mainUrl$it"
            }
            
            newMovieSearchResponse(title, fullUrl, TvType.Anime) {
                this.posterUrl = thumbnail
                this.description = "Animator/Studio Highlight"
            }
        }
        
        return newHomePageResponse("Highlights", items, hasNext = items.isNotEmpty())
    }

    private suspend fun getUpdatesPage(page: Int): HomePageResponse {
        val pageUrl = if (page > 1) "$updatesUrl?page=$page" else updatesUrl
        val document = app.get(pageUrl).document
        
        val items = document.select("a[href*='/player/']").mapNotNull { element ->
            element.toSearchResponse()
        }.distinctBy { it.url }
        
        return newHomePageResponse("Latest Updates", items, hasNext = items.isNotEmpty())
    }

    private suspend fun getBingePage(): HomePageResponse {
        val document = app.get(bingeUrl).document
        
        val items = document.select("a[href*='/player/']").mapNotNull { element ->
            element.toSearchResponse()
        }.distinctBy { it.url }
        
        return newHomePageResponse("Binge! Anime Series", items, hasNext = items.isNotEmpty())
    }

    private suspend fun getSortedPage(url: String, page: Int, name: String): HomePageResponse {
        val pageUrl = if (page > 1) "$url&page=$page" else url
        val document = app.get(pageUrl).document
        
        val items = document.select("a[href*='/player/']").mapNotNull { element ->
            element.toSearchResponse()
        }.distinctBy { it.url }
        
        return newHomePageResponse(name, items, hasNext = items.isNotEmpty())
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        // Look for video links with thumbnails
        val link = this.selectFirst("a[href*='/player/']") ?: return null
        val href = link.attr("href")
        
        if (href.isBlank()) return null
        
        val fullUrl = if (href.startsWith("http")) href else "$mainUrl$href"
        
        // Get title from various locations
        val title = this.selectFirst("span")?.text()?.trim()
            ?: link.selectFirst("img")?.attr("alt")?.trim()
            ?: link.selectFirst("p")?.text()?.trim()
            ?: return null
        
        // Get thumbnail
        val thumbnail = link.selectFirst("img")?.attr("src")?.let { 
            if (it.startsWith("http")) it else "$mainUrl$it"
        }
        
        // Determine type based on title/content
        val isMovie = title.contains("Movie", ignoreCase = true) || 
                     href.contains("/movie/", ignoreCase = true)
        val isSeries = title.contains("Season", ignoreCase = true) || 
                      href.contains("/videos/", ignoreCase = true)
        
        val tvType = when {
            isMovie -> TvType.AnimeMovie
            isSeries -> TvType.Anime
            else -> TvType.OVA
        }
        
        return newMovieSearchResponse(title, fullUrl, tvType) {
            this.posterUrl = thumbnail
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$playerUrl/?recherche=${URLEncoder.encode(query, "UTF-8")}"
        val document = app.get(searchUrl).document
        
        return document.select("a[href*='/player/']").mapNotNull { element ->
            element.toSearchResponse()
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        
        // Extract title
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
        
        // Check if it's a series page with episodes
        val hasEpisodes = url.contains("/videos/") && url.contains("/1")
        val isMovie = title.contains("Movie", ignoreCase = true) || 
                     (!hasEpisodes && !title.contains("Season", ignoreCase = true))
        
        if (isMovie || !hasEpisodes) {
            // Single video or movie
            return newMovieLoadResponse(title, url, TvType.AnimeMovie, url) {
                this.posterUrl = thumbnail
                this.plot = description
            }
        } else {
            // Series with episodes - extract from URL pattern
            val baseUrl = url.substringBeforeLast("/")
            val episodes = (1..12).map { episodeNum ->
                val episodeUrl = "$baseUrl/$episodeNum"
                newEpisode(episodeUrl) {
                    this.name = "Episode $episodeNum"
                    this.episode = episodeNum
                    this.season = 1
                }
            }
            
            return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
                this.posterUrl = thumbnail
                this.plot = description
            }
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
        
        // Method 2: Look for YouTube/Vimeo iframes
        document.select("iframe[src*='youtube.com'], iframe[src*='youtu.be'], iframe[src*='vimeo.com']").forEach { iframe ->
            val iframeSrc = iframe.attr("src")
            if (iframeSrc.isNotBlank()) {
                foundLinks = true
                loadExtractor(fixUrl(iframeSrc), data, subtitleCallback, callback)
            }
        }
        
        // Method 3: Look for video.js player
        document.select("video[data-setup], [data-video-id]").forEach { video ->
            val videoId = video.attr("data-video-id").ifBlank { video.attr("id") }
            if (videoId.isNotBlank()) {
                // Try YouTube or Vimeo
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
        
        // Method 4: Check scripts for embedded URLs
        if (!foundLinks) {
            document.select("script").forEach { script ->
                val scriptText = script.html()
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
