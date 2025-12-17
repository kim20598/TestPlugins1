package com.animesuge.provider

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.net.URLEncoder
import java.util.Base64

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

    override val mainPage = mainPageOf(
        "$mainUrl/latest-updated" to "Recently Updated",
        "$mainUrl/new-release" to "New Releases", 
        "$mainUrl/most-viewed" to "Popular Anime",
        "$mainUrl/status/finished-airing" to "Completed",
        "$mainUrl/status/currently-airing" to "Ongoing"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        try {
            val document = app.get(request.data + if (page > 1) "?page=$page" else "").document
            
            // Try different selectors for anime items
            val home = document.select(".anime-card, .anime-item, .item, a[href*='/watch/']")
                .filterNot { it.attr("href").isNullOrBlank() }
                .mapNotNull {
                    val title = it.selectFirst(".name, .title, h3, h4, .anime-name")?.text()?.trim() 
                        ?: it.attr("title").takeIf { t -> t.isNotBlank() }
                        ?: return@mapNotNull null
                    
                    val href = fixUrl(it.attr("href"))
                    if (href.contains("/watch/").not()) return@mapNotNull null
                    
                    val image = it.selectFirst("img")?.attr("src")?.takeIf { src -> src.isNotBlank() }?.let { src ->
                        if (src.startsWith("http")) src else "$mainUrl$src"
                    }
                    
                    newAnimeSearchResponse(title, href) {
                        this.posterUrl = image
                    }
                }.distinctBy { it.url }
            
            return newHomePageResponse(request.name, home)
        } catch (e: Exception) {
            throw ErrorLoadingException("Failed to load ${request.name}")
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val document = app.get("$mainUrl/filter?keyword=$encodedQuery").document
            
            document.select(".anime-card, .anime-item, .item, a[href*='/watch/']")
                .filterNot { it.attr("href").isNullOrBlank() }
                .mapNotNull {
                    val title = it.selectFirst(".name, .title, h3, h4, .anime-name")?.text()?.trim() 
                        ?: it.attr("title").takeIf { t -> t.isNotBlank() }
                        ?: return@mapNotNull null
                    
                    val href = fixUrl(it.attr("href"))
                    if (href.contains("/watch/").not()) return@mapNotNull null
                    
                    val image = it.selectFirst("img")?.attr("src")?.takeIf { src -> src.isNotBlank() }?.let { src ->
                        if (src.startsWith("http")) src else "$mainUrl$src"
                    }
                    
                    newAnimeSearchResponse(title, href) {
                        this.posterUrl = image
                    }
                }.distinctBy { it.url }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        return try {
            val document = app.get(url).document
            
            // Get title - try multiple selectors
            val title = document.selectFirst("h1.title, h1, .title, [itemprop=name]")?.text()?.trim() 
                ?: "Unknown Title"
            
            // Get poster - try multiple selectors
            val poster = document.selectFirst(".poster img, [itemprop=image], .thumbnail img, img[src*='cdn']")?.attr("src")?.let { src ->
                if (src.startsWith("http")) src else "$mainUrl$src"
            }
            
            // Get plot - try multiple selectors
            val plot = document.selectFirst(".description, .plot, .synopsis, [itemprop=description]")?.text()?.trim()
            
            // Get episodes - try multiple selectors
            val episodes = mutableListOf<Episode>()
            
            // Try episode container selectors
            val episodeContainers = listOf(
                "#media-episode",
                ".episode-list",
                ".range",
                ".episodes"
            )
            
            for (container in episodeContainers) {
                val episodeElements = document.select("$container a[href*='/watch/'], $container a[href*='/ep-']")
                if (episodeElements.isNotEmpty()) {
                    for (ep in episodeElements) {
                        val episodeUrl = fixUrl(ep.attr("href"))
                        if (episodeUrl.isBlank()) continue
                        
                        // Extract episode number
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
                    }
                    break // Stop after finding episodes in first valid container
                }
            }
            
            // Check if it's a movie (no episodes or explicitly marked as movie)
            val typeText = document.selectFirst(".meta:contains(Type), .info:contains(Type)")?.text()?.lowercase() ?: ""
            val isMovie = typeText.contains("movie") || 
                         url.contains("/movie/") || 
                         document.select("h1:contains(movie), .title:contains(movie)").isNotEmpty() ||
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
            // If everything fails, return a basic response
            newMovieLoadResponse("Error Loading", url, TvType.AnimeMovie, url) {
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
            
            // Method 1: Try servers with data-link-id
            val servers = document.select(".server[data-link-id], [data-link-id]")
            for (server in servers) {
                val dataLinkId = server.attr("data-link-id")
                if (dataLinkId.isNotBlank()) {
                    // Try to decode base64 first
                    try {
                        val decoded = Base64.getDecoder().decode(dataLinkId)
                        val decodedString = String(decoded)
                        if (decodedString.startsWith("http")) {
                            if (loadExtractor(decodedString, subtitleCallback, callback)) {
                                foundLinks = true
                                break
                            }
                        }
                    } catch (e: Exception) {
                        // If decoding fails, try direct Megaplay URL
                        val megaUrl = "https://megaplay.buzz/stream/s-1/$dataLinkId?autostart=true"
                        if (loadExtractor(megaUrl, subtitleCallback, callback)) {
                            foundLinks = true
                            break
                        }
                    }
                }
            }
            
            // Method 2: Try iframe
            if (!foundLinks) {
                val iframe = document.selectFirst("iframe[src]")
                val iframeSrc = iframe?.attr("src")?.takeIf { it.isNotBlank() }?.let { src ->
                    if (src.startsWith("http")) src else "https:$src"
                }
                if (iframeSrc != null) {
                    foundLinks = loadExtractor(iframeSrc, subtitleCallback, callback)
                }
            }
            
            // Method 3: Look for video URLs in scripts
            if (!foundLinks) {
                val scripts = document.select("script:not([src])")
                for (script in scripts) {
                    val scriptText = script.html()
                    // Look for various video URL patterns
                    val patterns = listOf(
                        Regex("""['"](https?://[^'"]*\.(mp4|m3u8|webm)[^'"]*)['"]"""),
                        Regex("""(https?://[^'"\s]+\.(mp4|m3u8|webm))"""),
                        Regex("""src\s*[:=]\s*['"](https?://[^'"]+)['"]"""),
                        Regex("""file\s*[:=]\s*['"](https?://[^'"]+)['"]""")
                    )
                    
                    for (pattern in patterns) {
                        val matches = pattern.findAll(scriptText)
                        for (match in matches) {
                            val url = match.groupValues[1]
                            if (url.contains("video") || url.contains(".mp4") || url.contains(".m3u8")) {
                                if (loadExtractor(url, subtitleCallback, callback)) {
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
            
            // Method 4: Try to find direct video elements
            if (!foundLinks) {
                val videoElements = document.select("video source[src], video[src], [data-video-src], [data-src*='.mp4'], [data-src*='.m3u8']")
                for (video in videoElements) {
                    val src = video.attr("src").ifBlank { 
                        video.attr("data-src").ifBlank { 
                            video.attr("data-video-src") 
                        }
                    }
                    if (src.isNotBlank() && (src.contains(".mp4") || src.contains(".m3u8"))) {
                        val fullUrl = if (src.startsWith("http")) src else "https:$src"
                        if (loadExtractor(fullUrl, subtitleCallback, callback)) {
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
}
