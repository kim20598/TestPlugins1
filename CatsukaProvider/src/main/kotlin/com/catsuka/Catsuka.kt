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
        "$mainUrl/player/updates/" to "New Entries",
        "$mainUrl/player/highlights/" to "Highlights",
        "$mainUrl/player/binge/" to "Binge",
        "$mainUrl/player/categorie/courtmetrage" to "Short Films",
        "$mainUrl/player/categorie/clip" to "Music Videos"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        try {
            val url = request.data + if (page > 1) "?page=$page" else ""
            val document = app.get(url).document
            
            // Find all swiper containers for different sections
            val swiperContainers = document.select(".swiper-container")
            
            val home = mutableListOf<SearchResponse>()
            
            swiperContainers.forEach { container ->
                // Get slides from each swiper
                val slides = container.select(".swiper-slide")
                slides.forEach { slide ->
                    val searchResult = slide.toSearchResult()
                    if (searchResult != null) {
                        home.add(searchResult)
                    }
                }
            }
            
            return newHomePageResponse(request.name, home.distinctBy { it.url }, hasNext = home.isNotEmpty())
        } catch (e: Exception) {
            return newHomePageResponse(request.name, emptyList())
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        // Look for the link element
        val link = this.selectFirst("a[href]") ?: return null
        val href = fixUrl(link.attr("href"))
        
        // Make sure it's a player link
        if (!href.contains("/player/")) return null
        
        // Get title from span or p tag
        val title = this.selectFirst("span, p")?.text()?.trim()
            ?: link.selectFirst("img")?.attr("alt")?.trim()
            ?: link.selectFirst("img")?.attr("title")?.trim()
            ?: return null
        
        if (title.isBlank()) return null
        
        // Get poster from image
        val posterUrl = fixUrlNull(
            link.selectFirst("img")?.attr("src")
            ?: link.selectFirst("img")?.attr("data-src")
        )?.let { if (it.startsWith("http")) it else "$mainUrl$it" }
        
        return newAnimeSearchResponse(title, href) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            // Catsuka uses POST search
            val document = app.post(
                "$mainUrl/player/?recherche",
                data = mapOf("recherche" to query)
            ).document
            
            // Look for search results - they might be in swiper slides
            val results = mutableListOf<SearchResponse>()
            
            // Check swiper containers first
            val swiperContainers = document.select(".swiper-container")
            swiperContainers.forEach { container ->
                val slides = container.select(".swiper-slide")
                slides.forEach { slide ->
                    val searchResult = slide.toSearchResult()
                    if (searchResult != null) {
                        results.add(searchResult)
                    }
                }
            }
            
            // Also check for any direct links
            val links = document.select("a[href*='/player/']").take(50)
            links.forEach { link ->
                val href = fixUrl(link.attr("href"))
                if (href.contains("/player/") && !href.contains("/player/?recherche")) {
                    val title = link.selectFirst("img")?.attr("alt")?.trim()
                        ?: link.selectFirst("img")?.attr("title")?.trim()
                        ?: link.text().trim()
                    
                    if (title.isNotBlank()) {
                        val posterUrl = fixUrlNull(
                            link.selectFirst("img")?.attr("src")
                            ?: link.selectFirst("img")?.attr("data-src")
                        )
                        
                        results.add(newAnimeSearchResponse(title, href) {
                            this.posterUrl = posterUrl
                        })
                    }
                }
            }
            
            results.distinctBy { it.url }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        return try {
            val document = app.get(url).document
            
            // Get title
            val title = document.selectFirst("h1.title, h1, .title, .caption span:first-child")?.text()?.trim()
                ?: "Unknown Title"
            
            // Get poster - look in multiple places
            val poster = fixUrlNull(
                document.selectFirst(".poster img, .cover img, img[src*='vignettes']")?.attr("src")
                ?: document.selectFirst("img[src*='vignettes']")?.attr("src")
                ?: document.selectFirst("meta[property='og:image']")?.attr("content")
            )
            
            // Get plot/description
            val plot = document.selectFirst(".description, .plot, .summary, p")?.text()?.trim()
            
            // Check if it's a series (has episodes)
            val hasEpisodes = url.contains("/videos/") && url.contains("/1")
            
            if (hasEpisodes) {
                // Parse episode count from URL or page
                val episodeLinks = document.select("a[href*='/videos/']")
                val episodes = mutableListOf<Episode>()
                
                // Try to find episode list
                episodeLinks.forEachIndexed { index, link ->
                    val epUrl = fixUrl(link.attr("href"))
                    if (epUrl.contains("/videos/") && epUrl != url) {
                        val epTitle = link.text().trim().ifBlank { "Episode ${index + 1}" }
                        episodes.add(
                            newEpisode(epUrl) {
                                name = epTitle
                                this.episode = index + 1
                            }
                        )
                    }
                }
                
                // If no episodes found, create dummy ones
                if (episodes.isEmpty()) {
                    episodes.add(newEpisode(url) {
                        name = "Episode 1"
                        this.episode = 1
                    })
                }
                
                newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
                    this.posterUrl = poster
                    this.plot = plot
                }
            } else {
                // It's a movie/single video
                newMovieLoadResponse(title, url, TvType.Movie, url) {
                    this.posterUrl = poster
                    this.plot = plot
                }
            }
            
        } catch (e: Exception) {
            newMovieLoadResponse("Error", url, TvType.Movie, url) {
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
        return try {
            // If data is a URL
            if (data.startsWith("http")) {
                val document = app.get(data).document
                
                // Look for iframe
                val iframe = document.selectFirst("iframe[src]")
                val iframeSrc = iframe?.attr("src")?.takeIf { it.isNotBlank() }
                    ?.let { if (it.startsWith("http")) it else "https:$it" }
                
                if (iframeSrc != null && loadExtractor(iframeSrc, subtitleCallback, callback)) {
                    return true
                }
                
                // Look for video elements
                val video = document.selectFirst("video source")
                val videoSrc = video?.attr("src")
                if (videoSrc != null && videoSrc.isNotBlank()) {
                    val videoUrl = if (videoSrc.startsWith("http")) videoSrc else "$mainUrl/$videoSrc"
                    // Use newExtractorLink function
                    callback(newExtractorLink(videoUrl, name, mainUrl, Qualities.Unknown.value, false))
                    return true
                }
                
                // Look for video scripts
                val scripts = document.select("script")
                for (script in scripts) {
                    val scriptText = script.html()
                    
                    // Look for Vimeo
                    val vimeoPattern = Regex("""vimeo\.com/(\d+)""")
                    val vimeoMatch = vimeoPattern.find(scriptText)
                    if (vimeoMatch != null) {
                        val videoId = vimeoMatch.groupValues[1]
                        val vimeoUrl = "https://player.vimeo.com/video/$videoId"
                        if (loadExtractor(vimeoUrl, subtitleCallback, callback)) {
                            return true
                        }
                    }
                    
                    // Look for YouTube
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
            }
            
            false
        } catch (e: Exception) {
            false
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

    private fun fixUrlNull(url: String?): String? {
        return url?.let { fixUrl(it) }
    }
}
