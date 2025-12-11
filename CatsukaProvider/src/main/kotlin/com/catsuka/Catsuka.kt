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

    // Define all category URLs for main page
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

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = request.data
        
        return try {
            when {
                // Home page - extract from main slider and sections
                url == playerUrl -> getHomePageSections(page, request.name)
                
                // Categories page - extract all categories
                url == categoriesUrl -> getAllCategoriesPage()
                
                // Specific category or section
                else -> {
                    val document = app.get(if (page > 1) "$url?page=$page" else url).document
                    
                    // Try multiple selectors for different page types
                    val items = extractItemsFromDocument(document, url)
                    
                    newHomePageResponse(
                        request.name,
                        items.distinctBy { it.url },
                        hasNext = items.isNotEmpty() && url != playerUrl
                    )
                }
            }
        } catch (e: Exception) {
            newHomePageResponse(request.name, emptyList())
        }
    }

    private suspend fun getHomePageSections(page: Int, sectionName: String): HomePageResponse {
        if (page > 1) return newHomePageResponse(sectionName, emptyList())
        
        val document = app.get(playerUrl).document
        val items = mutableListOf<HomePageList>()
        
        // 1. Featured Videos (Main Slider)
        val featuredItems = document.select(".main-slider .item.video a[href*='/player/']")
            .mapNotNull { element ->
                val href = element.attr("href")
                val fullUrl = fixUrl(href)
                val title = element.parent()?.selectFirst(".caption span:first-child")?.text()?.trim()
                    ?: element.selectFirst("img")?.attr("alt")?.trim()
                    ?: "Featured Video"
                
                val poster = element.parent()?.selectFirst("video")?.attr("poster")?.let { fixUrl(it) }
                    ?: element.selectFirst("img")?.attr("src")?.let { fixUrl(it) }
                
                newMovieSearchResponse(title, fullUrl, TvType.AnimeMovie) {
                    this.posterUrl = poster
                }
            }
        
        if (featuredItems.isNotEmpty()) {
            items.add(HomePageList("🎬 Featured Videos", featuredItems))
        }
        
        // 2. New Entries
        val newEntriesSection = document.select(".player-slider").getOrNull(1)
        val newEntries = newEntriesSection?.select(".swiper-slide")?.mapNotNull { slide ->
            slide.toSearchResponse()
        } ?: emptyList()
        
        if (newEntries.isNotEmpty()) {
            items.add(HomePageList("🆕 New Entries", newEntries))
        }
        
        // 3. Binge! Anime Series
        val bingeSection = document.select(".player-slider").getOrNull(2)
        val bingeItems = bingeSection?.select(".swiper-slide")?.mapNotNull { slide ->
            slide.toSearchResponse()
        } ?: emptyList()
        
        if (bingeItems.isNotEmpty()) {
            items.add(HomePageList("🍿 Binge! Anime Series", bingeItems))
        }
        
        // 4. Category Sections (3-8)
        for (i in 3..8) {
            val section = document.select(".player-slider").getOrNull(i)
            if (section != null) {
                val categoryName = section.selectFirst(".divorangegrand a")?.text()?.trim()
                    ?: section.selectFirst(".divorangegrand b")?.text()?.trim()
                    ?: "Category ${i-2}"
                
                val sectionItems = section.select(".swiper-slide").mapNotNull { slide ->
                    slide.toSearchResponse()
                }
                
                if (sectionItems.isNotEmpty()) {
                    items.add(HomePageList("📁 $categoryName", sectionItems))
                }
            }
        }
        
        return newHomePageResponse(items)
    }

    private suspend fun getAllCategoriesPage(): HomePageResponse {
        val document = app.get(categoriesUrl).document
        val items = mutableListOf<HomePageList>()
        
        document.select(".player-slider").forEachIndexed { index, section ->
            val categoryName = section.selectFirst(".divorangegrand b")?.text()?.trim()
                ?: "Category ${index + 1}"
            
            val categoryItems = section.select(".swiper-slide").mapNotNull { slide ->
                slide.toSearchResponse()
            }
            
            if (categoryItems.isNotEmpty()) {
                // Add emoji based on category name
                val emoji = when {
                    categoryName.contains("Short", ignoreCase = true) -> "🎬"
                    categoryName.contains("Pilot", ignoreCase = true) -> "✈️"
                    categoryName.contains("Episode", ignoreCase = true) -> "📺"
                    categoryName.contains("Music", ignoreCase = true) -> "🎵"
                    categoryName.contains("Commercial", ignoreCase = true) -> "📢"
                    categoryName.contains("Cinematic", ignoreCase = true) -> "🎮"
                    categoryName.contains("Opening", ignoreCase = true) -> "🎭"
                    categoryName.contains("Trailer", ignoreCase = true) -> "🎥"
                    categoryName.contains("Excerpt", ignoreCase = true) -> "📖"
                    categoryName.contains("Demoreel", ignoreCase = true) -> "🎨"
                    categoryName.contains("Sakuga", ignoreCase = true) -> "🇯🇵"
                    categoryName.contains("Making", ignoreCase = true) -> "🔧"
                    categoryName.contains("Tribute", ignoreCase = true) -> "❤️"
                    categoryName.contains("Other", ignoreCase = true) -> "📦"
                    categoryName.contains("Junk", ignoreCase = true) -> "😂"
                    categoryName.contains("Movie", ignoreCase = true) -> "🎞️"
                    else -> "📁"
                }
                
                items.add(HomePageList("$emoji $categoryName", categoryItems))
            }
        }
        
        return newHomePageResponse(items)
    }

    private fun extractItemsFromDocument(document: Element, baseUrl: String): List<SearchResponse> {
        val items = mutableListOf<SearchResponse>()
        
        // Method 1: Swiper slides (for category pages)
        items.addAll(document.select(".swiper-slide").mapNotNull { slide ->
            slide.toSearchResponse()
        })
        
        // Method 2: Direct video links
        items.addAll(document.select("a[href*='/player/']").mapNotNull { link ->
            val href = link.attr("href")
            if (href.isNotBlank() && !href.contains("categories") && !href.contains("highlights")) {
                val fullUrl = fixUrl(href)
                val title = link.selectFirst("span")?.text()?.trim()
                    ?: link.selectFirst("img")?.attr("alt")?.trim()
                    ?: link.selectFirst("p")?.text()?.trim()
                    ?: link.text().trim()
                
                if (title.isNotBlank()) {
                    val poster = link.selectFirst("img")?.attr("src")?.let { fixUrl(it) }
                    
                    val tvType = when {
                        title.contains("Movie", ignoreCase = true) || href.contains("/movie/") -> TvType.AnimeMovie
                        title.contains("Season", ignoreCase = true) || href.contains("/videos/") -> TvType.Anime
                        else -> TvType.OVA
                    }
                    
                    newMovieSearchResponse(title, fullUrl, tvType) {
                        this.posterUrl = poster
                    }
                } else null
            } else null
        })
        
        return items.distinctBy { it.url }
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        // Look for video link with thumbnail
        val link = this.selectFirst("a[href*='/player/']") ?: return null
        val href = link.attr("href")
        
        if (href.isBlank() || href.contains("categories") || href.contains("highlights")) {
            return null
        }
        
        val fullUrl = fixUrl(href)
        
        // Extract title
        val title = this.selectFirst("span")?.text()?.trim()
            ?: link.selectFirst("img")?.attr("alt")?.trim()
            ?: link.selectFirst("p")?.text()?.trim()
            ?: link.text().trim()
        
        if (title.isBlank()) return null
        
        // Extract thumbnail
        val poster = link.selectFirst("img")?.attr("src")?.let { fixUrl(it) }
        
        // Determine type
        val isMovie = title.contains("Movie", ignoreCase = true) || href.contains("/movie/")
        val isSeries = title.contains("Season", ignoreCase = true) || href.contains("/videos/")
        
        val tvType = when {
            isMovie -> TvType.AnimeMovie
            isSeries -> TvType.Anime
            else -> TvType.OVA
        }
        
        return newMovieSearchResponse(title, fullUrl, tvType) {
            this.posterUrl = poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        
        val searchUrl = "$playerUrl/?recherche=${URLEncoder.encode(query, "UTF-8")}"
        val document = app.get(searchUrl).document
        
        return extractItemsFromDocument(document, searchUrl).filter { 
            it.name.contains(query, ignoreCase = true)
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        
        // Extract metadata
        val title = document.selectFirst("meta[property='og:title']")?.attr("content")
            ?: document.selectFirst("h1")?.text()?.trim()
            ?: document.selectFirst("h2")?.text()?.trim()
            ?: "Catsuka Video"
        
        val description = document.selectFirst("meta[property='og:description']")?.attr("content")
            ?: document.selectFirst("meta[name=description]")?.attr("content")
            ?: "Animation video from Catsuka Player"
        
        val poster = document.selectFirst("meta[property='og:image']")?.attr("content")?.let { fixUrl(it) }
            ?: document.selectFirst("img")?.attr("src")?.let { fixUrl(it) }
        
        // Check if it's a series with episodes
        val hasEpisodes = url.contains("/videos/") && url.endsWith("/1")
        val isMovie = title.contains("Movie", ignoreCase = true) || 
                     (!hasEpisodes && !title.contains("Season", ignoreCase = true))
        
        return if (isMovie || !hasEpisodes) {
            // Single video
            newMovieLoadResponse(title, url, TvType.AnimeMovie, url) {
                this.posterUrl = poster
                this.plot = description
            }
        } else {
            // Series with episodes
            val baseUrl = url.substringBeforeLast("/")
            val episodes = (1..24).map { episodeNum ->
                val episodeUrl = "$baseUrl/$episodeNum"
                newEpisode(episodeUrl) {
                    this.name = "Episode $episodeNum"
                    this.episode = episodeNum
                    this.season = 1
                }
            }
            
            newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
                this.posterUrl = poster
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
        
        // Priority 1: Direct video elements
        document.select("video source[src], video[src]").forEach { source ->
            val videoUrl = source.attr("src").ifBlank { source.attr("data-src") }
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
        
        // Priority 2: Embedded players (YouTube/Vimeo)
        document.select("iframe[src*='youtube'], iframe[src*='youtu.be'], iframe[src*='vimeo']").forEach { iframe ->
            val iframeSrc = iframe.attr("src")
            if (iframeSrc.isNotBlank()) {
                foundLinks = true
                loadExtractor(fixUrl(iframeSrc), data, subtitleCallback, callback)
            }
        }
        
        // Priority 3: JavaScript video players
        if (!foundLinks) {
            document.select("script").forEach { script ->
                val scriptText = script.html()
                
                // Look for video URLs in JavaScript
                val patterns = listOf(
                    Regex("""["'](https?://[^"']*\.(?:mp4|m3u8|webm)[^"']*)["']"""),
                    Regex("""src\s*:\s*["'](https?://[^"']+)["']"""),
                    Regex("""file\s*:\s*["'](https?://[^"']+)["']"""),
                    Regex("""(https?://(?:www\.)?(?:youtube\.com/watch\?v=|youtu\.be/|vimeo\.com/)[^\s"']+)""")
                )
                
                patterns.forEach { pattern ->
                    pattern.findAll(scriptText).forEach { match ->
                        val videoUrl = match.groupValues[1]
                        if (videoUrl.isNotBlank() && 
                            (videoUrl.contains("youtube") || videoUrl.contains("vimeo") || 
                             videoUrl.contains(".mp4") || videoUrl.contains(".m3u8"))) {
                            foundLinks = true
                            
                            if (videoUrl.contains("youtube") || videoUrl.contains("vimeo")) {
                                loadExtractor(fixUrl(videoUrl), data, subtitleCallback, callback)
                            } else {
                                callback(
                                    ExtractorLink(
                                        source = name,
                                        name = "Embedded Video",
                                        url = fixUrl(videoUrl),
                                        referer = data,
                                        quality = Qualities.Unknown.value,
                                        type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 
                                               else ExtractorLinkType.VIDEO
                                    )
                                )
                            }
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
            else -> "$mainUrl/$url"
        }
    }
}
