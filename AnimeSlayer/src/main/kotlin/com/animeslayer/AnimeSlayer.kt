package com.animeslayer

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLEncoder

class AnimeSlayer : MainAPI() {
    override var mainUrl = "https://animeslayerweb.com"
    override var name = "AnimeSlayer"
    override val hasMainPage = true
    override var lang = "ar"  // Changed from "en" to "ar" since it's Arabic
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "أحدث الحلقات",
        "$mainUrl/anime/?status=ongoing" to "الأنمي المستمر",
        "$mainUrl/anime/?status=completed&order=rating" to "الأعلى تقييماً",
        "$mainUrl/anime/?status=completed" to "الأنمي المكتمل"
    )

    // Fixed: Correct selector for anime cards
    private fun Element.toAnimeSearchResult(): SearchResponse? {
        val href = this.selectFirst("a")?.attr("href")?.takeIf { it.isNotBlank() } ?: return null
        val fixedHref = fixUrl(href)
        
        val title = this.selectFirst(".tt h2, .tt, h2, .entry-title, .title")?.text()?.trim() 
            ?: this.selectFirst("img")?.attr("alt")?.trim()
            ?: return null
        
        val posterUrl = fixUrlNull(
            this.selectFirst("img[src], img[data-src]")?.let { img ->
                img.attr("src").takeIf { it.isNotBlank() }
                    ?: img.attr("data-src").takeIf { it.isNotBlank() }
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
            
            // Fixed: Use correct selectors from the HTML
            val items = mutableListOf<SearchResponse>()
            
            // Anime cards are in articles with class "bs"
            val animeElements = document.select("article.bs, .bsx, .listupd article, .excstf article")
            
            animeElements.mapNotNull { it.toAnimeSearchResult() }.forEach { items.add(it) }
            
            // Fallback: look for any article
            if (items.isEmpty()) {
                document.select("article").mapNotNull { it.toAnimeSearchResult() }.forEach { items.add(it) }
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
            
            // Search results in articles
            document.select("article.bs, .bsx, article").mapNotNull { element ->
                element.toAnimeSearchResult()
            }.distinctBy { it.url }
            
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        return try {
            val document = app.get(url).document
            
            // Get title
            val title = document.selectFirst("h1.entry-title, h1.title, h1")?.text()?.trim()
                ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
                ?: "Unknown Title"
            
            // Get poster
            val poster = fixUrlNull(
                document.selectFirst("meta[property=og:image]")?.attr("content")
                    ?: document.selectFirst(".thumb img, .thumbook img, img.ts-post-image")?.attr("src")
            )
            
            // Get plot/description
            val plot = document.selectFirst(".desc, .entry-content, .synopsis")?.text()?.trim()
                ?: document.selectFirst("meta[name=description]")?.attr("content")?.trim()
            
            // Extract episodes - Fixed selector
            val episodes = mutableListOf<Episode>()
            
            // Look for the episodes container
            val episodeContainer = document.selectFirst("#EpList1")
            
            if (episodeContainer != null) {
                // Each episode is in a .CSB div
                val episodeElements = episodeContainer.select(".CSB")
                
                episodeElements.forEachIndexed { index, epEl ->
                    val episodeId = epEl.attr("id") // e.g., "IDSB1", "IDSB2"
                    val episodeName = epEl.text().trim()
                    
                    // The episode number is the last part of the ID or extracted from text
                    val episodeNum = try {
                        episodeId.replace("IDSB", "").toIntOrNull() ?: (index + 1)
                    } catch (e: Exception) {
                        index + 1
                    }
                    
                    // In AnimeSlayer, episodes are loaded via JavaScript
                    // We need to use the URL with episode parameter
                    val episodeUrl = "$url?ep=$episodeNum"
                    
                    episodes.add(
                        newEpisode(episodeUrl) {
                            this.name = episodeName
                            this.episode = episodeNum
                            this.season = 1
                        }
                    )
                }
            } else {
                // Check if it's a movie (has server list but no episode list)
                val hasServers = document.select(".ul-server-position1, .server-list").isNotEmpty()
                val episodeSpan = document.selectFirst("span:contains(الحلقات:)")?.text()
                val isMovie = hasServers && episodeSpan == null
                
                if (!isMovie) {
                    // Try to extract episodes from other parts
                    val episodeInfo = document.selectFirst("span:contains(الحلقات:)")
                    if (episodeInfo != null) {
                        val epText = episodeInfo.text()
                        val epCount = "\\d+".toRegex().find(epText)?.value?.toIntOrNull()
                        
                        if (epCount != null && epCount > 0) {
                            for (i in 1..epCount) {
                                episodes.add(
                                    newEpisode("$url?ep=$i") {
                                        this.name = "الحلقة $i"
                                        this.episode = i
                                        this.season = 1
                                    }
                                )
                            }
                        }
                    }
                }
            }
            
            // Check type from the page
            val typeElement = document.selectFirst("span:contains(النوع:)")
            val typeText = typeElement?.text()?.lowercase() ?: ""
            
            val isMovieType = typeText.contains("movie") || 
                             document.select(".typez.Movie").isNotEmpty() ||
                             episodes.isEmpty() && document.select(".ul-server-position1").isNotEmpty()
            
            if (isMovieType) {
                return newMovieLoadResponse(title, url, TvType.AnimeMovie, url) {
                    this.posterUrl = poster
                    this.plot = plot
                }
            }
            
            if (episodes.isNotEmpty()) {
                newTvSeriesLoadResponse(title, url, TvType.Anime, episodes.sortedBy { it.episode }) {
                    this.posterUrl = poster
                    this.plot = plot
                }
            } else {
                // Default to movie if no episodes found
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
                val url = if (data.contains("?ep=")) {
                    // It's an episode URL
                    val baseUrl = data.substringBefore("?ep=")
                    val epNum = data.substringAfter("?ep=").toIntOrNull() ?: 1
                    baseUrl
                } else {
                    data
                }
                
                val document = app.get(url).document
                
                // Extract episode number from URL
                val epNum = if (data.contains("?ep=")) {
                    data.substringAfter("?ep=").toIntOrNull() ?: 1
                } else {
                    1
                }
                
                // Find the server list for this episode
                // Each episode has its own .divv11 container with servers
                val serverContainers = document.select(".divv11")
                
                if (serverContainers.size >= epNum) {
                    val targetContainer = serverContainers[epNum - 1]
                    val servers = targetContainer.select(".ul-server-position1 li")
                    
                    servers.forEach { server ->
                        // Get data-url attribute which contains the real link
                        val dataUrl = server.attr("data-url").takeIf { it.isNotBlank() }
                            ?: server.attr("data").takeIf { it.isNotBlank() }
                        
                        if (dataUrl != null) {
                            val serverName = server.text().trim()
                            val quality = server.attr("quality-data")?.takeIf { it.isNotBlank() }
                                ?: if (serverName.contains("FHD")) "1080p"
                                else if (serverName.contains("HD")) "720p"
                                else if (serverName.contains("SD")) "480p"
                                else "Unknown"
                            
                            // Check server type and handle accordingly
                            val serverType = server.attr("type") ?: server.attr("class")
                            
                            when {
                                serverType.contains("vanfem") -> {
                                    // Vanfem server - needs special handling
                                    val vanfemUrl = "https://vanfem.com/e/$dataUrl"
                                    callback(
                                        ExtractorLink(
                                            name,
                                            "$serverName ($quality)",
                                            vanfemUrl,
                                            "",
                                            Qualities.Unknown.value,
                                            false
                                        )
                                    )
                                    return true
                                }
                                serverType.contains("mega") || serverType.contains("drive") -> {
                                    // MEGA or Google Drive links
                                    callback(
                                        ExtractorLink(
                                            name,
                                            "$serverName ($quality)",
                                            "https://direct-link.com/$dataUrl", // You'll need to handle these
                                            "",
                                            Qualities.Unknown.value,
                                            false
                                        )
                                    )
                                    return true
                                }
                                else -> {
                                    // Try to load extractor
                                    if (loadExtractor(dataUrl, mainUrl, subtitleCallback, callback)) {
                                        return true
                                    }
                                }
                            }
                        }
                    }
                }
                
                // Fallback: Look for iframes
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
