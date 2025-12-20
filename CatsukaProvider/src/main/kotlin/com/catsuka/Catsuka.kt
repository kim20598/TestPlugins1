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

    // SIMPLE WORKING URLs from the HTML you shared
    override val mainPage = mainPageOf(
        // Direct video URLs that we KNOW work (from your HTML)
        "$mainUrl/player/the_primary_cilia" to "🎬 Test Video 1",
        "$mainUrl/player/vorace" to "🎬 Test Video 2",
        "$mainUrl/player/snow_bear" to "🎬 Test Video 3",
        
        // Try the actual player homepage
        "$mainUrl/player/" to "🏠 Catsuka Home"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = request.data
        
        try {
            val doc = app.get(url).document
            
            // STRATEGY 1: If this is a video page, show "Same category" videos
            if (url.contains("/player/") && !url.endsWith("/player/")) {
                return parseVideoPageAsMainPage(doc, request.name)
            }
            
            // STRATEGY 2: Try to find videos on the homepage
            val items = findVideosOnPage(doc)
            
            return newHomePageResponse(request.name, items, hasNext = false)
            
        } catch (e: Exception) {
            // Fallback: Return test items
            return newHomePageResponse(request.name, createTestItems(), hasNext = false)
        }
    }
    
    private suspend fun parseVideoPageAsMainPage(
        doc: Element,
        pageName: String
    ): HomePageResponse {
        val items = mutableListOf<SearchResponse>()
        
        // Look for "Same category" videos (from your HTML)
        doc.select(".swiper-slide").forEach { slide ->
            val link = slide.selectFirst("a")
            val href = link?.attr("href") ?: return@forEach
            val img = slide.selectFirst("img")
            val title = img?.attr("alt") ?: link.text() ?: "Unknown"
            val poster = img?.attr("src") ?: img?.attr("data-src")
            
            if (title.isNotBlank() && href.contains("/player/")) {
                items.add(
                    newMovieSearchResponse(title, fixUrl(href), TvType.Movie) {
                        this.posterUrl = fixUrlNull(poster)
                    }
                )
            }
        }
        
        // If no swiper slides found, try other patterns
        if (items.isEmpty()) {
            doc.select("a[href*='/player/']").forEach { link ->
                val href = link.attr("href")
                val title = link.text().trim()
                val img = link.selectFirst("img")
                val poster = img?.attr("src") ?: img?.attr("data-src")
                
                if (title.isNotBlank() && !title.contains("CGU") && !title.contains("Privacy")) {
                    items.add(
                        newMovieSearchResponse(title, fixUrl(href), TvType.Movie) {
                            this.posterUrl = fixUrlNull(poster)
                        }
                    )
                }
            }
        }
        
        return newHomePageResponse(pageName, items.take(10), hasNext = false)
    }
    
    private fun findVideosOnPage(doc: Element): List<SearchResponse> {
        val items = mutableListOf<SearchResponse>()
        
        // Try multiple patterns from the HTML you provided
        val patterns = listOf(
            // Pattern 1: Direct links with images (like in the highlight page)
            Triple("a[href*='/player/']:has(img)", "img", "href"),
            // Pattern 2: Links in zonetableau (highlight page)
            Triple(".zonetableau li a", "span", "href"),
            // Pattern 3: Any player link with text
            Triple("a[href*='/player/']", "self", "href")
        )
        
        for ((selector, titleSource, hrefAttr) in patterns) {
            doc.select(selector).forEach { element ->
                try {
                    val href = element.attr(hrefAttr)
                    if (!href.contains("/player/") || href.contains("/cgu/") || href.contains("/privacy/")) {
                        return@forEach
                    }
                    
                    val title = when (titleSource) {
                        "img" -> element.selectFirst("img")?.attr("alt") ?: element.text().trim()
                        "span" -> element.selectFirst("span")?.text() ?: element.text().trim()
                        else -> element.text().trim()
                    }
                    
                    val poster = element.selectFirst("img")?.attr("src") 
                        ?: element.selectFirst("img")?.attr("data-src")
                    
                    if (title.isNotBlank() && title.length > 2) {
                        items.add(
                            newMovieSearchResponse(title, fixUrl(href), TvType.Movie) {
                                this.posterUrl = fixUrlNull(poster)
                            }
                        )
                    }
                } catch (e: Exception) {
                    // Skip this element
                }
            }
            
            if (items.isNotEmpty()) break // Stop at first successful pattern
        }
        
        return items.distinctBy { it.url }.take(15)
    }
    
    private fun createTestItems(): List<SearchResponse> {
        // Create manual test items so we SEE something in the app
        return listOf(
            newMovieSearchResponse("The Primary Cilia", "$mainUrl/player/the_primary_cilia", TvType.Movie) {
                this.posterUrl = "$mainUrl/videos/player/vignettes/the_primary_cilia.jpg"
            },
            newMovieSearchResponse("Vorace", "$mainUrl/player/vorace", TvType.Movie) {
                this.posterUrl = "$mainUrl/videos/player/vignettes/vorace.jpg"
            },
            newMovieSearchResponse("Snow Bear", "$mainUrl/player/snow_bear", TvType.Movie) {
                this.posterUrl = "$mainUrl/videos/player/vignettes/snow_bear.jpg"
            }
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        
        try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val searchUrl = "$mainUrl/player/?recherche=$encodedQuery"
            val doc = app.get(searchUrl).document
            
            return findVideosOnPage(doc)
        } catch (e: Exception) {
            return emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        try {
            val doc = app.get(url).document
            
            // Extract title
            val title = doc.selectFirst("h1, .txtblanc25, .post__name")?.text()?.trim() 
                ?: "Unknown Title"
            
            // Extract poster
            val poster = fixUrlNull(
                doc.selectFirst("meta[property='og:image']")?.attr("content")
                    ?: doc.selectFirst("meta[itemprop='image']")?.attr("content")
                    ?: doc.selectFirst("img[src*='vignettes']")?.attr("src")
                    ?: doc.selectFirst("img")?.attr("src")
            )
            
            // Extract info
            var director: String? = null
            var production: String? = null
            
            doc.select("b").forEach { b ->
                val text = b.text()
                if (text.contains("Director", ignoreCase = true)) {
                    director = b.nextSibling()?.toString()?.trim()
                }
                if (text.contains("Production", ignoreCase = true)) {
                    production = b.nextSibling()?.toString()?.trim()
                }
            }
            
            // Build plot
            val plotBuilder = StringBuilder()
            if (director != null) plotBuilder.append("Director: $director\n")
            if (production != null) plotBuilder.append("Production: $production\n")
            
            // Check if this has episodes (universal player)
            val hasPlayer = doc.select(".universal_video_player_list").isNotEmpty()
            
            if (hasPlayer) {
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
                
                if (episodes.isNotEmpty()) {
                    return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                        this.posterUrl = poster
                        this.plot = plotBuilder.toString()
                    }
                }
            }
            
            // Single video
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plotBuilder.toString()
            }
            
        } catch (e: Exception) {
            return newMovieLoadResponse("Error", url, TvType.Movie, url) {
                this.plot = "Failed to load video"
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Simple extractor - just pass to default extractors
        return when {
            data.matches(Regex("\\d+")) -> {
                // Vimeo ID
                val vimeoUrl = "https://player.vimeo.com/video/$data"
                loadExtractor(vimeoUrl, "$mainUrl/player/", subtitleCallback, callback)
            }
            data.matches(Regex("[A-Za-z0-9_-]{11}")) -> {
                // YouTube ID
                val youtubeUrl = "https://www.youtube.com/watch?v=$data"
                loadExtractor(youtubeUrl, "$mainUrl/player/", subtitleCallback, callback)
            }
            data.startsWith("http") -> {
                // Direct URL
                loadExtractor(data, "$mainUrl/player/", subtitleCallback, callback)
            }
            else -> {
                false
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
