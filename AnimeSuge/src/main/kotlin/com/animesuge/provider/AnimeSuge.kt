package com.animesuge.provider

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLEncoder

class AnimeSuge : MainAPI() {
    override var mainUrl = "https://animesuge.to"
    override var name = "AnimeSuge"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    override val mainPage = listOf(
        MainPageData("Recently Updated", "$mainUrl/latest-updated", true),
        MainPageData("New Releases", "$mainUrl/new-release", true),
        MainPageData("Popular Anime", "$mainUrl/most-viewed", true),
        MainPageData("Completed", "$mainUrl/status/finished-airing", true),
        MainPageData("Ongoing", "$mainUrl/status/currently-airing", true)
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        try {
            val url = request.data + if (page > 1) "?page=$page" else ""
            val document = app.get(url).document
            
            val home = mutableListOf<SearchResponse>()
            
            // Try multiple selectors
            val selectors = listOf(
                ".anime.main-card .item",
                ".anime.mini-card .item",
                ".anime-card .item",
                "a.item[href*='/watch/']",
                ".swiper-slide[href*='/watch/']"
            )
            
            for (selector in selectors) {
                val items = document.select(selector)
                if (items.isNotEmpty()) {
                    for (item in items) {
                        try {
                            val titleElement = item.selectFirst(".name, .detail .name, .item-bottom .name, span")
                            val title = titleElement?.text()?.trim() ?: continue
                            
                            val href = item.attr("href").takeIf { it.isNotBlank() } 
                                ?: item.selectFirst("a[href]")?.attr("href")
                                ?: continue
                            
                            val fullUrl = if (href.startsWith("http")) href else "$mainUrl$href"
                            if (!fullUrl.contains("/watch/")) continue
                            
                            val image = item.selectFirst("img")?.attr("src")?.takeIf { it.isNotBlank() }?.let { img ->
                                if (img.startsWith("http")) img else "$mainUrl$img"
                            } ?: item.selectFirst("img")?.attr("data-src")?.takeIf { it.isNotBlank() }?.let { img ->
                                if (img.startsWith("http")) img else "$mainUrl$img"
                            }
                            
                            home.add(
                                newAnimeSearchResponse(title, fullUrl) {
                                    this.posterUrl = image
                                }
                            )
                        } catch (e: Exception) {
                            // Skip individual item errors
                        }
                    }
                    break
                }
            }
            
            // Fallback
            if (home.isEmpty()) {
                val allLinks = document.select("a[href*='/watch/']")
                for (link in allLinks) {
                    try {
                        val title = link.attr("title").takeIf { it.isNotBlank() }
                            ?: link.text().trim().takeIf { it.isNotBlank() }
                            ?: continue
                        
                        val href = link.attr("href")
                        val fullUrl = if (href.startsWith("http")) href else "$mainUrl$href"
                        
                        val image = link.selectFirst("img")?.attr("src")?.takeIf { it.isNotBlank() }?.let { img ->
                            if (img.startsWith("http")) img else "$mainUrl$img"
                        }
                        
                        home.add(
                            newAnimeSearchResponse(title, fullUrl) {
                                this.posterUrl = image
                            }
                        )
                    } catch (e: Exception) {
                        // Skip individual link errors
                    }
                }
            }
            
            return newHomePageResponse(request.name, home.distinctBy { it.url })
        } catch (e: Exception) {
            return newHomePageResponse(request.name, emptyList())
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val document = app.get("$mainUrl/filter?keyword=$encodedQuery").document
            
            val results = mutableListOf<SearchResponse>()
            
            val selectors = listOf(
                ".anime.main-card .item",
                ".anime.mini-card .item",
                ".anime-card .item",
                "a.item[href*='/watch/']"
            )
            
            for (selector in selectors) {
                val items = document.select(selector)
                for (item in items) {
                    try {
                        val titleElement = item.selectFirst(".name, .detail .name, .item-bottom .name")
                        val title = titleElement?.text()?.trim() ?: continue
                        
                        val href = item.attr("href").takeIf { it.isNotBlank() } 
                            ?: item.selectFirst("a[href]")?.attr("href")
                            ?: continue
                        
                        val fullUrl = if (href.startsWith("http")) href else "$mainUrl$href"
                        if (!fullUrl.contains("/watch/")) continue
                        
                        val image = item.selectFirst("img")?.attr("src")?.takeIf { it.isNotBlank() }?.let { img ->
                            if (img.startsWith("http")) img else "$mainUrl$img"
                        }
                        
                        results.add(
                            newAnimeSearchResponse(title, fullUrl) {
                                this.posterUrl = image
                            }
                        )
                    } catch (e: Exception) {
                        // Skip individual item errors
                    }
                }
            }
            
            results.distinctBy { it.url }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun load(loadUrl: String): LoadResponse {
        return try {
            val document = app.get(loadUrl).document
            
            // Get title
            val title = document.selectFirst("h1.title, h1, .title")?.text()?.trim() 
                ?: "Unknown Title"
            
            // Get poster
            val poster = document.selectFirst("#media-info .poster img, .poster img, [itemprop=image]")?.attr("src")?.let { src ->
                if (src.startsWith("http")) src else "$mainUrl$src"
            }
            
            // Get plot
            val plot = document.selectFirst(".description, .plot")?.text()?.trim()
            
            // Get episodes
            val episodes = mutableListOf<Episode>()
            
            // Look for episodes in multiple places
            val episodeSelectors = listOf(
                "#media-episode .range a[href*='/watch/']",
                ".range-wrap .range a[href*='/watch/']",
                ".range a[href*='/watch/']",
                "#media-episode a[href*='/watch/']",
                "a[href*='/watch/'][href*='/ep-']"
            )
            
            for (selector in episodeSelectors) {
                val episodeLinks = document.select(selector)
                if (episodeLinks.isNotEmpty()) {
                    for (ep in episodeLinks) {
                        try {
                            val episodeUrl = ep.attr("href").takeIf { it.isNotBlank() }?.let { href ->
                                if (href.startsWith("http")) href else "$mainUrl$href"
                            } ?: continue
                            
                            // Try multiple ways to get episode number
                            val episodeNumber = when {
                                ep.attr("data-slug").isNotBlank() -> ep.attr("data-slug").toIntOrNull()
                                ep.text().trim().matches(Regex("\\d+")) -> ep.text().trim().toIntOrNull()
                                episodeUrl.contains("ep-") -> {
                                    Regex("ep-(\\d+)").find(episodeUrl)?.groupValues?.get(1)?.toIntOrNull()
                                }
                                else -> null
                            } ?: continue
                            
                            val episodeName = ep.attr("title").takeIf { it.isNotBlank() }
                                ?: ep.attr("data-num").takeIf { it.isNotBlank() }
                                ?: "Episode $episodeNumber"
                            
                            episodes.add(
                                newEpisode(episodeUrl) {
                                    name = episodeName
                                    this.episode = episodeNumber
                                }
                            )
                        } catch (e: Exception) {
                            // Skip episode errors
                        }
                    }
                    break
                }
            }
            
            // Determine if it's a movie or series
            val typeText = document.selectFirst(".meta:contains(Type), .info:contains(Type)")?.text()?.lowercase() ?: ""
            val isExplicitlyMovie = typeText.contains("movie") || loadUrl.contains("/movie/")
            
            val hasSeasons = document.select("#ani-seasons, .media-season-head").isNotEmpty()
            val hasEpisodeRange = document.select(".range[data-range], .range-view").isNotEmpty()
            
            val isMovie = when {
                isExplicitlyMovie -> true
                episodes.isEmpty() && !hasSeasons -> true
                episodes.size == 1 && !hasSeasons && !hasEpisodeRange -> true
                else -> false
            }
            
            if (isMovie) {
                // For movies, use the first episode URL as dataUrl or the page URL itself
                val dataUrl = episodes.firstOrNull()?.url ?: loadUrl
                newMovieLoadResponse(title, loadUrl, TvType.AnimeMovie, dataUrl) {
                    this.posterUrl = poster
                    this.plot = plot
                }
            } else {
                newTvSeriesLoadResponse(title, loadUrl, TvType.Anime, episodes.sortedBy { it.episode }) {
                    this.posterUrl = poster
                    this.plot = plot
                }
            }
            
        } catch (e: Exception) {
            newTvSeriesLoadResponse("Error Loading", loadUrl, TvType.Anime, emptyList()) {
                this.plot = "Failed to load anime details. Please try again."
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
            val document = app.get(data).document
            var foundLinks = false
            
            // Method 1: Look for servers with data-link-id
            val servers = document.select(".server[data-link-id]")
            for (server in servers) {
                val dataLinkId = server.attr("data-link-id")
                if (dataLinkId.isNotBlank()) {
                    try {
                        val videoUrl = fetchVideoUrlFromApi(dataLinkId)
                        if (videoUrl != null && videoUrl.isNotBlank()) {
                            if (loadExtractor(videoUrl, subtitleCallback, callback)) {
                                foundLinks = true
                                break
                            }
                        }
                    } catch (e: Exception) {
                        // If API fails, try direct Megaplay URL
                        val megaUrl = "https://megaplay.buzz/stream/s-1/$dataLinkId"
                        if (loadExtractor(megaUrl, subtitleCallback, callback)) {
                            foundLinks = true
                            break
                        }
                    }
                }
            }
            
            // Method 2: Try iframe directly
            if (!foundLinks) {
                val iframe = document.selectFirst("iframe[src]")
                val iframeSrc = iframe?.attr("src")?.takeIf { it.isNotBlank() }?.let { src ->
                    if (src.startsWith("http")) src else "https:$src"
                }
                if (iframeSrc != null && loadExtractor(iframeSrc, subtitleCallback, callback)) {
                    foundLinks = true
                }
            }
            
            // Method 3: Look for video URLs in scripts
            if (!foundLinks) {
                val scripts = document.select("script")
                for (script in scripts) {
                    val scriptText = script.html()
                    
                    // Look for various video patterns
                    val patterns = listOf(
                        Regex("""['"](https?://[^'"]*\.(mp4|m3u8|webm)[^'"]*)['"]"""),
                        Regex("""(https?://[^'"\s]+\.(mp4|m3u8|webm))"""),
                        Regex("""megaplay\.buzz/stream/s-1/([^"'\s?]+)"""),
                        Regex("""src\s*[:=]\s*['"](https?://[^'"]+)['"]""")
                    )
                    
                    for (pattern in patterns) {
                        val matches = pattern.findAll(scriptText)
                        for (match in matches) {
                            val urlOrId = match.groupValues[1]
                            if (urlOrId.isNotBlank()) {
                                val urlToTry = when {
                                    urlOrId.startsWith("http") -> urlOrId
                                    pattern.pattern.contains("megaplay") -> "https://megaplay.buzz/stream/s-1/$urlOrId"
                                    else -> null
                                }
                                
                                if (urlToTry != null && loadExtractor(urlToTry, subtitleCallback, callback)) {
                                    foundLinks = true
                                    break
                                }
                            }
                        }
                        if (foundLinks) break
                    }
                    if (foundLinks) break
                }
            }
            
            foundLinks
            
        } catch (e: Exception) {
            false
        }
    }
    
    private suspend fun fetchVideoUrlFromApi(dataLinkId: String): String? {
        return try {
            // Make API call to get the actual video URL
            val apiUrl = "$mainUrl/ajax/server?get=$dataLinkId"
            val response = app.get(apiUrl)
            
            // Parse JSON response
            val json = response.parsedSafe<VideoApiResponse>()
            json?.result?.url
        } catch (e: Exception) {
            null
        }
    }
    
    data class VideoApiResponse(
        val status: Int? = null,
        val message: String? = null,
        val result: VideoResult? = null
    )
    
    data class VideoResult(
        val url: String? = null,
        val skip_data: Any? = null
    )
}
