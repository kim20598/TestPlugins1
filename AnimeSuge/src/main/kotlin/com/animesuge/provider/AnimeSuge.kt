package com.animesuge.provider

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.Jsoup
import java.net.URLEncoder
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.getQualityFromName
import org.jsoup.nodes.Element
import java.util.Base64

class AnimeSuge : MainAPI() {
    override var mainUrl = "https://animesuge.bz"
    override var name = "Animesuge"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(
        TvType.Anime, TvType.AnimeMovie, TvType.OVA
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val categories = listOf(
            "$mainUrl/latest-updated" to "Recently Updated",
            "$mainUrl/new-release" to "New Releases", 
            "$mainUrl/most-viewed" to "Popular Anime",
            "$mainUrl/status/finished-airing" to "Completed",
            "$mainUrl/status/currently-airing" to "Ongoing",
            "$mainUrl/status/not-yet-aired" to "Upcoming"
        )

        val items = mutableListOf<HomePageList>()
        
        for ((url, title) in categories) {
            try {
                val fullUrl = if (page > 1) "$url?page=$page" else url
                val doc = app.get(fullUrl).document
                
                // Correct selectors based on actual homepage structure
                val animeList = doc.select(".anime.mini-card .item, .anime.main-card .item, a[href*='/watch/']").mapNotNull { item ->
                    val titleElement = item.selectFirst(".name, p.name, .detail .name, .item-bottom .name")
                    val titleText = titleElement?.text()?.trim() ?: return@mapNotNull null
                    
                    // Get href - handle both relative and absolute URLs
                    val href = when {
                        item.hasAttr("abs:href") -> item.attr("abs:href")
                        item.hasAttr("href") -> {
                            val relativeHref = item.attr("href")
                            if (relativeHref.startsWith("http")) relativeHref else "$mainUrl$relativeHref"
                        }
                        else -> return@mapNotNull null
                    }.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    
                    val poster = item.selectFirst("img")?.attr("src")?.takeIf { it.isNotBlank() } ?: 
                                item.selectFirst("img")?.attr("data-src")?.takeIf { it.isNotBlank() }
                    
                    newAnimeSearchResponse(titleText, href) {
                        this.posterUrl = poster
                    }
                }.distinctBy { it.url }

                if (animeList.isNotEmpty()) {
                    items.add(HomePageList(title, animeList))
                }
            } catch (e: Exception) {
                // Continue with next category if one fails
            }
        }

        // If no categories work, try the homepage directly
        if (items.isEmpty()) {
            try {
                val doc = app.get("$mainUrl/home").document
                
                // Try to get anime from the "Recently Updated" section
                val updatedList = doc.select(".original.anime.main-card .item").mapNotNull { item ->
                    val titleElement = item.selectFirst(".name, .item-bottom .name a")
                    val titleText = titleElement?.text()?.trim() ?: return@mapNotNull null
                    
                    val href = item.selectFirst("a[href*='/watch/']")?.attr("abs:href")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    
                    val poster = item.selectFirst("img")?.attr("src")?.takeIf { it.isNotBlank() } ?: 
                                item.selectFirst("img")?.attr("data-src")?.takeIf { it.isNotBlank() }
                    
                    newAnimeSearchResponse(titleText, href) {
                        this.posterUrl = poster
                    }
                }
                
                if (updatedList.isNotEmpty()) {
                    items.add(HomePageList("Recently Updated", updatedList))
                }
                
                // Try to get from "Recently Added" section
                val addedList = doc.select(".hot-stat .added .anime.mini-card .item").mapNotNull { item ->
                    val titleElement = item.selectFirst(".name, .detail .name")
                    val titleText = titleElement?.text()?.trim() ?: return@mapNotNull null
                    
                    val href = item.attr("abs:href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    
                    val poster = item.selectFirst("img")?.attr("src")?.takeIf { it.isNotBlank() } ?: 
                                item.selectFirst("img")?.attr("data-src")?.takeIf { it.isNotBlank() }
                    
                    newAnimeSearchResponse(titleText, href) {
                        this.posterUrl = poster
                    }
                }
                
                if (addedList.isNotEmpty()) {
                    items.add(HomePageList("Recently Added", addedList))
                }
                
            } catch (e: Exception) {
                // If everything fails, throw error
            }
        }

        if (items.isEmpty()) throw ErrorLoadingException()
        return newHomePageResponse(items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val searchUrl = "$mainUrl/filter?keyword=$encodedQuery"
        
        return try {
            val doc = app.get(searchUrl).document
            
            // Try multiple selectors for search results
            val results = mutableListOf<SearchResponse>()
            
            // Method 1: Try main card items
            val mainCards = doc.select(".anime.main-card .item").mapNotNull { item ->
                val titleElement = item.selectFirst(".name, .item-bottom .name a")
                val title = titleElement?.text()?.trim() ?: return@mapNotNull null
                
                val href = item.selectFirst("a[href*='/watch/']")?.attr("abs:href") ?: return@mapNotNull null
                val poster = item.selectFirst("img")?.attr("src") ?: item.selectFirst("img")?.attr("data-src")
                
                newAnimeSearchResponse(title, href) {
                    this.posterUrl = poster
                }
            }
            results.addAll(mainCards)
            
            // Method 2: Try mini card items
            val miniCards = doc.select(".anime.mini-card .item").mapNotNull { item ->
                val titleElement = item.selectFirst(".name, .detail .name")
                val title = titleElement?.text()?.trim() ?: return@mapNotNull null
                
                val href = item.attr("abs:href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val poster = item.selectFirst("img")?.attr("src") ?: item.selectFirst("img")?.attr("data-src")
                
                newAnimeSearchResponse(title, href) {
                    this.posterUrl = poster
                }
            }
            results.addAll(miniCards)
            
            // Method 3: Try any watch links as fallback
            if (results.isEmpty()) {
                val watchLinks = doc.select("a[href*='/watch/']").mapNotNull { item ->
                    val title = item.attr("title").ifBlank { item.text().trim() }
                    if (title.isBlank()) return@mapNotNull null
                    
                    val href = item.attr("abs:href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val poster = item.selectFirst("img")?.attr("src") ?: item.selectFirst("img")?.attr("data-src")
                    
                    newAnimeSearchResponse(title, href) {
                        this.posterUrl = poster
                    }
                }
                results.addAll(watchLinks)
            }
            
            results.distinctBy { it.url }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun extractEpisodes(doc: org.jsoup.nodes.Document, currentUrl: String): List<Episode> {
        // Look for ALL episode links in the episode container
        val episodeElements = doc.select("#media-episode a[href*='/watch/'], .range a[href*='/watch/']")
        
        if (episodeElements.isEmpty()) {
            // Check if it's a movie by looking for explicit movie indicators
            val isMovie = doc.select(".meta div:contains(Type) + span").any { 
                it.text().contains("movie", true) 
            } || currentUrl.contains("/movie/") || doc.select("h1, .title").any { 
                it.text().contains("movie", true) 
            }
            
            if (isMovie) {
                return emptyList() // It's a movie, return empty episodes
            }
            
            // Check if it's "coming soon" or "not yet aired"
            val status = doc.select(".meta div:contains(Status) + span").text().orEmpty()
            if (status.contains("not yet aired", true) || status.contains("coming soon", true)) {
                // It's an upcoming anime with no episodes yet
                return emptyList()
            }
            
            // If we're on an episode page itself, create just that episode
            if (currentUrl.contains("/ep-")) {
                val episodeNumber = Regex("""ep-(\d+)""").find(currentUrl)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                return listOf(
                    newEpisode(currentUrl) {
                        name = "Episode $episodeNumber"
                        this.episode = episodeNumber
                    }
                )
            }
            
            // If no episodes found but it's a series, return empty list
            return emptyList()
        }
        
        // Extract ALL real episodes
        return episodeElements.mapNotNull { episodeElement ->
            val episodeUrl = episodeElement.attr("abs:href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            
            // Extract episode number from multiple possible sources
            val episodeNumber = episodeElement.attr("data-slug").toIntOrNull() ?: 
                               episodeElement.text().trim().toIntOrNull() ?: 
                               Regex("""ep-(\d+)""").find(episodeUrl)?.groupValues?.get(1)?.toIntOrNull() ?:
                               Regex("""/ep-(\d+)""").find(episodeUrl)?.groupValues?.get(1)?.toIntOrNull()
            
            // Skip if we can't determine episode number
            if (episodeNumber == null) return@mapNotNull null
            
            val episodeTitle = episodeElement.attr("title").ifBlank { 
                episodeElement.attr("data-num").ifBlank {
                    episodeElement.text().trim().ifBlank {
                        "Episode $episodeNumber"
                    }
                }
            }
            
            newEpisode(episodeUrl) {
                name = episodeTitle
                this.episode = episodeNumber
            }
        }.sortedBy { it.episode ?: 0 }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        
        // Extract main metadata from the actual HTML structure
        val title = doc.selectFirst("h1.title, .title, h1, [itemprop=name]")?.text()?.trim() ?: "Unknown"
        val poster = doc.selectFirst("#media-info .poster img, [itemprop=image]")?.attr("src")
        val plot = doc.selectFirst(".description, .plot, [itemprop=description]")?.text()?.trim()
        
        // Extract additional metadata to help determine type
        val type = doc.selectFirst(".meta div:contains(Type) + span")?.text()?.trim()
        val status = doc.selectFirst(".meta div:contains(Status) + span")?.text()?.trim()
        val totalEpisodes = doc.selectFirst(".meta div:contains(Episodes) + span")?.text()?.toIntOrNull()
        
        // Extract episodes - pass the current URL to the function
        val episodes = extractEpisodes(doc, url)
        
        // Debug: Print how many episodes were found
        println("DEBUG: Found ${episodes.size} episodes for $title")
        if (episodes.isNotEmpty()) {
            println("DEBUG: Episode numbers: ${episodes.map { it.episode }}")
        }
        
        // Better logic to determine if it's a movie or series
        val isMovie = when {
            // Explicit movie indicators
            type?.contains("movie", true) == true -> true
            url.contains("/movie/") -> true
            doc.select("h1, .title").any { it.text().contains("movie", true) } -> true
            
            // Series indicators - if we found episodes, it's a series
            episodes.isNotEmpty() -> false
            totalEpisodes != null && totalEpisodes > 1 -> false
            
            // Check status for upcoming anime
            status?.contains("not yet aired", true) == true -> false
            status?.contains("coming soon", true) == true -> false
            
            // Default to movie if no episodes found and no series indicators
            else -> true
        }

        if (isMovie) {
            return newMovieLoadResponse(title, url, TvType.AnimeMovie, url) {
                this.posterUrl = poster
                this.plot = plot
            }
        }
        
        // For upcoming anime with no episodes yet
        if (episodes.isEmpty() && (status?.contains("not yet aired", true) == true || status?.contains("coming soon", true) == true)) {
            return newTvSeriesLoadResponse(title, url, TvType.Anime, emptyList()) {
                this.posterUrl = poster
                this.plot = plot
            }
        }
        
        return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val episodeUrl = data
        
        return try {
            val episodeDoc = app.get(episodeUrl).document
            var foundSources = false
            
            // Method 1: First, look for the active server iframe
            val activeServer = episodeDoc.select(".server.active")
            if (activeServer.isNotEmpty()) {
                // Get the active server's data-link-id
                val dataLinkId = activeServer.attr("data-link-id")
                if (dataLinkId.isNotBlank()) {
                    // For Megaplay server, construct the URL
                    val serverName = activeServer.select("span").text().lowercase()
                    if (serverName.contains("megaplay")) {
                        val videoUrl = "https://megaplay.buzz/stream/s-1/$dataLinkId?autostart=true"
                        foundSources = loadExtractor(videoUrl, subtitleCallback, callback) || foundSources
                    }
                }
                
                // Also check for iframe
                val iframe = episodeDoc.select("iframe[src]").firstOrNull()
                if (iframe != null) {
                    val iframeSrc = iframe.attr("abs:src")
                    if (iframeSrc.isNotBlank()) {
                        foundSources = loadExtractor(iframeSrc, subtitleCallback, callback) || foundSources
                    }
                }
            }
            
            // Method 2: Look for all server elements
            val serverElements = episodeDoc.select(".server, .server-list .server")
            for (server in serverElements) {
                val serverName = server.select("span, div").text().trim()
                val dataLinkId = server.attr("data-link-id")
                
                if (dataLinkId.isNotBlank() && !foundSources) {
                    try {
                        // Try to construct URL based on server name
                        val videoUrl = when {
                            serverName.contains("Megaplay", true) -> {
                                "https://megaplay.buzz/stream/s-1/$dataLinkId?autostart=true"
                            }
                            serverName.contains("Kiwi", true) -> {
                                // Kiwi streams might need special handling
                                "https://kiwistream.pro/player/$dataLinkId"
                            }
                            else -> null
                        }
                        
                        if (videoUrl != null) {
                            foundSources = loadExtractor(videoUrl, subtitleCallback, callback) || foundSources
                        }
                    } catch (e: Exception) {
                        // Ignore errors for individual servers
                    }
                }
            }
            
            // Method 3: Look for scripts that might contain video data
            if (!foundSources) {
                val scripts = episodeDoc.select("script")
                for (script in scripts) {
                    val scriptText = script.html()
                    
                    // Look for various video URL patterns
                    val patterns = listOf(
                        Regex("""src\s*:\s*["'](https?://[^"']+)["']"""),
                        Regex("""file\s*:\s*["'](https?://[^"']+)["']"""),
                        Regex("""video_url\s*:\s*["'](https?://[^"']+)["']"""),
                        Regex("""(https?://[^\s"']*\.(mp4|m3u8|webm)[^\s"']*)"""),
                        Regex("""data-link-id\s*=\s*["']([^"']+)["']""")
                    )
                    
                    for (pattern in patterns) {
                        val matches = pattern.findAll(scriptText)
                        for (match in matches) {
                            val urlOrId = match.groupValues[1]
                            if (urlOrId.isNotBlank()) {
                                if (urlOrId.startsWith("http")) {
                                    // Direct URL
                                    if (urlOrId.contains(".mp4") || urlOrId.contains(".m3u8") || urlOrId.contains(".webm")) {
                                        callback(
                                            newExtractorLink(
                                                name = "Direct",
                                                url = urlOrId,
                                                source = this.name,
                                                type = when {
                                                    urlOrId.contains(".m3u8") -> ExtractorLinkType.M3U8
                                                    else -> ExtractorLinkType.VIDEO
                                                }
                                            ) {
                                                this.quality = getQualityFromName(urlOrId) ?: Qualities.Unknown.value
                                            }
                                        )
                                        foundSources = true
                                    } else {
                                        foundSources = loadExtractor(urlOrId, subtitleCallback, callback) || foundSources
                                    }
                                } else if (urlOrId.length > 50) {
                                    // Might be a data-link-id
                                    try {
                                        // Try Megaplay URL
                                        val megaUrl = "https://megaplay.buzz/stream/s-1/$urlOrId?autostart=true"
                                        foundSources = loadExtractor(megaUrl, subtitleCallback, callback) || foundSources
                                    } catch (e: Exception) {
                                        // Ignore
                                    }
                                }
                            }
                        }
                    }
                }
            }
            
            // Method 4: Look for direct video elements
            val videoElements = episodeDoc.select("video source[src], video[src]")
            for (videoSource in videoElements) {
                val src = videoSource.attr("abs:src").ifBlank { 
                    videoSource.attr("data-src").ifBlank {
                        videoSource.attr("src")
                    }
                }
                if (src.isNotBlank() && (src.contains(".mp4") || src.contains(".m3u8") || src.contains(".webm"))) {
                    callback(
                        newExtractorLink(
                            name = "Direct Video",
                            url = src,
                            source = this.name,
                            type = when {
                                src.contains(".m3u8") -> ExtractorLinkType.M3U8
                                else -> ExtractorLinkType.VIDEO
                            }
                        ) {
                            this.quality = getQualityFromName(src) ?: Qualities.Unknown.value
                        }
                    )
                    foundSources = true
                }
            }
            
            // Method 5: Look for iframes as final fallback
            if (!foundSources) {
                val iframes = episodeDoc.select("iframe[src]")
                for (iframe in iframes) {
                    val iframeSrc = iframe.attr("abs:src")
                    if (iframeSrc.isNotBlank()) {
                        foundSources = loadExtractor(iframeSrc, subtitleCallback, callback) || foundSources
                        if (foundSources) break
                    }
                }
            }
            
            foundSources
            
        } catch (e: Exception) {
            false
        }
    }
}
