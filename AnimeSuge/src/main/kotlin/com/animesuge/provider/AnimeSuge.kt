package com.animesuge.provider

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.net.URLEncoder

class AnimeSuge : MainAPI() {
    override var mainUrl = "https://animesuge.bz"
    override var name = "Animesuge"
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
            
            val selectors = listOf(
                ".anime.main-card .item",
                ".anime.mini-card .item",
                ".anime-card .item",
                "a.item[href*='/watch/']",
                ".swiper-slide[href*='/watch/']",
                ".original.anime.main-card .item"
            )
            
            for (selector in selectors) {
                val items = document.select(selector)
                if (items.isNotEmpty()) {
                    for (item in items) {
                        try {
                            val titleElement = item.selectFirst(".name, .detail .name, .item-bottom .name, span, .swiper-inner span")
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

    override suspend fun load(url: String): LoadResponse {
        return try {
            val document = app.get(url).document
            
            val title = document.selectFirst("h1.title, h1, .title")?.text()?.trim() 
                ?: "Unknown Title"
            
            val poster = document.selectFirst("#media-info .poster img, .poster img, [itemprop=image]")?.attr("src")?.let { src ->
                if (src.startsWith("http")) src else "$mainUrl$src"
            }
            
            val plot = document.selectFirst(".description, .plot")?.text()?.trim()
            
            val episodes = mutableListOf<Episode>()
            
            val rangeContainer = document.selectFirst(".range, #media-episode .range, .range-wrap .range")
            if (rangeContainer != null) {
                val episodeLinks = rangeContainer.select("a[href*='/watch/'], a[href*='/ep-']")
                for (ep in episodeLinks) {
                    try {
                        val episodeUrl = ep.attr("href").takeIf { it.isNotBlank() }?.let { href ->
                            if (href.startsWith("http")) href else "$mainUrl$href"
                        } ?: continue
                        
                        val episodeNumber = ep.attr("data-slug").toIntOrNull()
                            ?: ep.text().trim().toIntOrNull()
                            ?: Regex("""ep-(\d+)""").find(episodeUrl)?.groupValues?.get(1)?.toIntOrNull()
                            ?: continue
                        
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
            }
            
            val typeElement = document.selectFirst(".meta div:contains(Type) + span")
            val isMovie = typeElement?.text()?.contains("movie", true) == true || 
                         url.contains("/movie/") || 
                         episodes.isEmpty()
            
            if (isMovie) {
                newMovieLoadResponse(title, url, TvType.AnimeMovie, url) {
                    this.posterUrl = poster
                    this.plot = plot
                }
            } else {
                newTvSeriesLoadResponse(title, url, TvType.Anime, episodes.sortedBy { it.episode }) {
                    this.posterUrl = poster
                    this.plot = plot
                }
            }
            
        } catch (e: Exception) {
            newMovieLoadResponse("Error", url, TvType.AnimeMovie, url) {
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
            val document = app.get(data).document
            var foundLinks = false
            
            // NEW: Try API call first (from the JavaScript code you analyzed)
            val servers = document.select(".server[data-link-id]")
            for (server in servers) {
                val dataLinkId = server.attr("data-link-id")
                if (dataLinkId.isNotBlank()) {
                    try {
                        // Make API call to get actual video URL
                        val videoUrl = fetchVideoUrlFromApi(dataLinkId)
                        if (videoUrl != null && videoUrl.isNotBlank()) {
                            if (loadExtractor(videoUrl, subtitleCallback, callback)) {
                                foundLinks = true
                                break
                            }
                        }
                    } catch (e: Exception) {
                        // If API fails, fall back to old Megaplay method
                        val megaUrl = "https://megaplay.buzz/stream/s-1/$dataLinkId?autostart=true"
                        if (loadExtractor(megaUrl, subtitleCallback, callback)) {
                            foundLinks = true
                            break
                        }
                    }
                }
            }
            
            // Your existing fallback methods
            if (!foundLinks) {
                val iframe = document.selectFirst("iframe[src]")
                val iframeSrc = iframe?.attr("src")?.takeIf { it.isNotBlank() }?.let { src ->
                    if (src.startsWith("http")) src else "https:$src"
                }
                if (iframeSrc != null) {
                    foundLinks = loadExtractor(iframeSrc, subtitleCallback, callback)
                }
            }
            
            if (!foundLinks) {
                val scripts = document.select("script")
                for (script in scripts) {
                    val scriptText = script.html()
                    val megaPattern = Regex("""megaplay\.buzz/stream/s-1/([^"'\s?]+)""")
                    val match = megaPattern.find(scriptText)
                    if (match != null) {
                        val videoId = match.groupValues[1]
                        val megaUrl = "https://megaplay.buzz/stream/s-1/$videoId?autostart=true"
                        if (loadExtractor(megaUrl, subtitleCallback, callback)) {
                            foundLinks = true
                            break
                        }
                    }
                }
            }
            
            foundLinks
            
        } catch (e: Exception) {
            false
        }
    }
    
    // NEW FUNCTION: Fetch video URL from API (from your JavaScript analysis)
    private suspend fun fetchVideoUrlFromApi(dataLinkId: String): String? {
        return try {
            // Make API call to get the actual video URL
            val apiUrl = "$mainUrl/ajax/server?get=$dataLinkId"
            val response = app.get(apiUrl)
            
            // Parse JSON response
            val json = response.parsedSafe<VideoApiResponse>()
            
            // Check if response is successful
            if (json?.status == 200) {
                json.result?.url
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
    
    // NEW: Data classes for JSON parsing
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
