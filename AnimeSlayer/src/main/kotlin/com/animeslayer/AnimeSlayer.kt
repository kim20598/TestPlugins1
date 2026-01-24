package com.animeslayer

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLEncoder

class AnimeSlayer : MainAPI() {
    override var mainUrl = "https://animeslayerweb.com"
    override var name = "AnimeSlayer"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    // Main page sections from AnimeSlayer
    override val mainPage = mainPageOf(
        "$mainUrl/" to "Latest Updates",
        "$mainUrl/?s=" to "Trending",
        "$mainUrl/anime-list/" to "All Anime",
        "$mainUrl/status/airing/" to "Currently Airing",
        "$mainUrl/status/finished/" to "Completed",
        "$mainUrl/category/movies/" to "Anime Movies",
        "$mainUrl/category/ona/" to "ONA",
        "$mainUrl/category/ova/" to "OVA",
        "$mainUrl/category/special/" to "Specials"
    )

    // Parse anime cards from main page
    private fun Element.toAnimeSearchResult(): SearchResponse? {
        val title = this.selectFirst("h3, h4, .title, .entry-title")?.text()?.trim() ?: return null
        val href = this.selectFirst("a")?.attr("href")?.takeIf { it.isNotBlank() } ?: return null
        val fixedHref = fixUrl(href)
        
        // Get poster from various possible locations
        val posterUrl = fixUrlNull(
            this.selectFirst("img[src], img[data-src]")?.let { img ->
                img.attr("src").takeIf { it.isNotBlank() }
                    ?: img.attr("data-src").takeIf { it.isNotBlank() }
                    ?: img.attr("data-lazy-src").takeIf { it.isNotBlank() }
            }
        )
        
        return newAnimeSearchResponse(title, fixedHref) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        return try {
            val url = if (page > 1) {
                "${request.data}page/$page/"
            } else {
                request.data
            }
            
            val document = app.get(url).document
            
            // Multiple selectors to catch all anime cards
            val items = mutableListOf<SearchResponse>()
            
            // Try different selectors used by AnimeSlayer
            val selectors = listOf(
                ".anime-card",
                ".post",
                ".item",
                ".hentry",
                "article",
                ".grid-item",
                ".col"
            )
            
            for (selector in selectors) {
                val elements = document.select(selector)
                if (elements.isNotEmpty()) {
                    elements.mapNotNull { it.toAnimeSearchResult() }.forEach { items.add(it) }
                    break
                }
            }
            
            // Fallback: look for any anchor with anime in href
            if (items.isEmpty()) {
                document.select("a[href*=/anime/]").forEach { link ->
                    val href = link.attr("href")
                    if (href.contains("/anime/") && !href.contains("/category/")) {
                        val title = link.text().trim()
                        if (title.isNotBlank()) {
                            items.add(newAnimeSearchResponse(title, fixUrl(href)) {})
                        }
                    }
                }
            }
            
            newHomePageResponse(request.name, items.distinctBy { it.url }, hasNext = items.isNotEmpty())
        } catch (e: Exception) {
            newHomePageResponse(request.name, emptyList())
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val searchUrl = "$mainUrl/?s=$encodedQuery"
            val document = app.get(searchUrl).document
            
            val items = mutableListOf<SearchResponse>()
            
            // Search results typically in articles or posts
            document.select("article, .post, .search-result").mapNotNull { element ->
                element.toAnimeSearchResult()
            }.distinctBy { it.url }
            
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        return try {
            val document = app.get(url).document
            
            // Get title - from h1 or og:title
            val title = document.selectFirst("h1.entry-title, h1.title, h1")?.text()?.trim()
                ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
                ?: document.selectFirst("title")?.text()?.substringBefore(" -")?.trim()
                ?: "Unknown Title"
            
            // Get poster - from og:image or featured image
            val poster = fixUrlNull(
                document.selectFirst("meta[property=og:image]")?.attr("content")
                    ?: document.selectFirst(".post-thumbnail img, .featured-image img")?.attr("src")
            )
            
            // Get plot/description
            val plot = document.selectFirst(".entry-content, .description, .synopsis")?.text()?.trim()
                ?: document.selectFirst("meta[name=description]")?.attr("content")?.trim()
            
            // Extract episodes from EpList1 container
            val episodes = mutableListOf<Episode>()
            val episodeContainer = document.selectFirst("#EpList1")
            
            if (episodeContainer != null) {
                // Look for episodes in .CSB divs or similar
                val episodeElements = episodeContainer.select(".CSB, .episode-item, .episode")
                
                episodeElements.forEachIndexed { index, epEl ->
                    val episodeLink = epEl.selectFirst("a")?.attr("href")?.takeIf { it.isNotBlank() }
                    if (episodeLink != null) {
                        val episodeName = epEl.text().trim().takeIf { it.isNotBlank() }
                            ?: "Episode ${index + 1}"
                        
                        episodes.add(
                            newEpisode(fixUrl(episodeLink)) {
                                this.name = episodeName
                                this.episode = index + 1
                                this.season = 1
                            }
                        )
                    }
                }
            }
            
            // If no episodes found in EpList1, check for server list
            if (episodes.isEmpty()) {
                // Check if it's a movie (has server list but no episode list)
                val hasServers = document.select(".ul-server-position1, .server-list").isNotEmpty()
                
                if (hasServers) {
                    // It's a movie or single episode
                    return newMovieLoadResponse(title, url, TvType.AnimeMovie, url) {
                        this.posterUrl = poster
                        this.plot = plot
                    }
                }
            }
            
            // Determine if it's a TV series or movie
            val isTvSeries = episodes.isNotEmpty() || url.contains("/anime/")
            
            if (isTvSeries && episodes.isNotEmpty()) {
                newTvSeriesLoadResponse(title, url, TvType.Anime, episodes.sortedBy { it.episode }) {
                    this.posterUrl = poster
                    this.plot = plot
                }
            } else {
                newMovieLoadResponse(title, url, TvType.AnimeMovie, url) {
                    this.posterUrl = poster
                    this.plot = plot
                }
            }
            
        } catch (e: Exception) {
            newMovieLoadResponse("Error Loading", url, TvType.AnimeMovie, url) {
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
            if (data.startsWith("http")) {
                val document = app.get(data).document
                
                // METHOD 1: Extract from server list (ul-server-position1)
                val serverList = document.selectFirst(".ul-server-position1, .server-list")
                if (serverList != null) {
                    val servers = serverList.select("li")
                    
                    servers.forEach { server ->
                        // Get data-url attribute which contains the real link
                        val dataUrl = server.attr("data-url").takeIf { it.isNotBlank() }
                            ?: server.selectFirst("a")?.attr("data-url")
                        
                        if (dataUrl != null) {
                            // The iframe is hidden but the data-url contains the real source
                            val serverName = server.text().trim().takeIf { it.isNotBlank() } ?: "Server"
                            
                            // Try to load the extractor
                            if (loadExtractor(dataUrl, mainUrl, subtitleCallback, callback)) {
                                return true
                            }
                        }
                    }
                }
                
                // METHOD 2: Look for hidden iframes with sandbox attribute
                val hiddenIframes = document.select("iframe[sandbox]")
                hiddenIframes.forEach { iframe ->
                    val src = iframe.attr("src").takeIf { it.isNotBlank() }
                    if (src != null && loadExtractor(src, mainUrl, subtitleCallback, callback)) {
                        return true
                    }
                }
                
                // METHOD 3: Look for video players in scripts
                val scripts = document.select("script")
                for (script in scripts) {
                    val scriptContent = script.html()
                    
                    // Look for video URLs in scripts
                    val patterns = listOf(
                        Regex("""src\s*:\s*['"](https?://[^'"]+)['"]"""),
                        Regex("""file\s*:\s*['"](https?://[^'"]+)['"]"""),
                        Regex("""url\s*:\s*['"](https?://[^'"]+)['"]"""),
                        Regex("""(https?://[^'"]+\.(?:m3u8|mp4|mkv)[^'"]*)""")
                    )
                    
                    for (pattern in patterns) {
                        val match = pattern.find(scriptContent)
                        if (match != null) {
                            val videoUrl = match.groupValues[1]
                            if (loadExtractor(videoUrl, mainUrl, subtitleCallback, callback)) {
                                return true
                            }
                        }
                    }
                }
                
                // METHOD 4: Check for standard iframes
                val iframes = document.select("iframe[src]")
                for (iframe in iframes) {
                    val src = iframe.attr("src").takeIf { it.isNotBlank() }
                    if (src != null && loadExtractor(src, mainUrl, subtitleCallback, callback)) {
                        return true
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
        return url?.takeIf { it.isNotBlank() }?.let { fixUrl(it) }
    }
}
