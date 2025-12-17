package com.animesuge.provider

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLEncoder

class AnimeSuge : MainAPI() {
    override var mainUrl = "https://animesuge.bz"
    override var name = "AnimeSuge"
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

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst(".name, .detail .name, h3, h4")?.text()?.trim() ?: return null
        val href = fixUrl(this.selectFirst("a")?.attr("href") ?: return null)
        val posterUrl = fixUrlNull(
            this.selectFirst("img")?.attr("src")?.takeIf { it.isNotBlank() }
            ?: this.selectFirst("img")?.attr("data-src")
        )?.let { if (it.startsWith("http")) it else "$mainUrl$it" }
        
        return newAnimeSearchResponse(title, href) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        try {
            val url = request.data + if (page > 1) "?page=$page" else ""
            val document = app.get(url).document
            
            val home = document.select(".item, .anime-card, .card, .anime-poster, .poster, article")
                .mapNotNull { it.toSearchResult() }
            
            return newHomePageResponse(request.name, home.distinctBy { it.url }, hasNext = true)
        } catch (e: Exception) {
            return newHomePageResponse(request.name, emptyList())
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val document = app.get("$mainUrl/filter?keyword=$encodedQuery").document
            
            document.select(".item, .anime-card, .card, .anime-poster, .poster, article")
                .mapNotNull { it.toSearchResult() }
                .distinctBy { it.url }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        return try {
            val document = app.get(url).document
            
            val title = document.selectFirst("h1.title, h1, .title")?.text()?.trim() ?: "Unknown Title"
            val poster = document.selectFirst(".poster img, [itemprop=image], .cover img")?.attr("src")
                ?.let { if (it.startsWith("http")) it else "$mainUrl$it" }
            val plot = document.selectFirst(".description, .plot, .summary")?.text()?.trim()
            
            // Check if it's a movie
            val typeText = document.selectFirst(".meta div:contains(Type) + span")?.text()?.trim()
            val isMovie = typeText?.contains("movie", true) == true || 
                         url.contains("/movie/", ignoreCase = true)
            
            val episodes = mutableListOf<Episode>()
            
            if (!isMovie) {
                // Get episode count from meta
                val episodeCountText = document.selectFirst(".meta div:contains(Episodes:) + span")?.text()
                val episodeCount = episodeCountText?.toIntOrNull() ?: 0
                
                // Also check for dub/sub counts
                val subCount = document.selectFirst(".dub-sub-total .sub")?.text()?.toIntOrNull() ?: 0
                val dubCount = document.selectFirst(".dub-sub-total .dub")?.text()?.toIntOrNull() ?: 0
                val totalCount = document.selectFirst(".dub-sub-total .total")?.text()?.toIntOrNull() ?: 0
                
                val finalEpisodeCount = maxOf(episodeCount, subCount, dubCount, totalCount)
                
                if (finalEpisodeCount > 0) {
                    // Try to extract anime ID from the page
                    val animeId = extractAnimeId(document, url)
                    
                    if (animeId != null) {
                        // Create episodes with anime ID as data
                        for (i in 1..finalEpisodeCount) {
                            val episodeData = "$animeId|$i"
                            
                            episodes.add(
                                newEpisode(episodeData) {
                                    name = "Episode $i"
                                    this.episode = i
                                }
                            )
                        }
                    } else {
                        // Fallback: generate episode URLs based on pattern
                        val baseUrl = url.substringBeforeLast("/ep-").substringBeforeLast("/")
                        for (i in 1..finalEpisodeCount) {
                            val episodeUrl = "$baseUrl/ep-$i"
                            episodes.add(
                                newEpisode(episodeUrl) {
                                    name = "Episode $i"
                                    this.episode = i
                                }
                            )
                        }
                    }
                }
            }
            
            if (isMovie || episodes.isEmpty()) {
                // For movies, try to extract movie ID
                val movieId = extractAnimeId(document, url) ?: url
                newMovieLoadResponse(title, movieId, TvType.AnimeMovie, url) {
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
            // Check if data contains anime ID and episode number
            if (data.contains("|")) {
                val parts = data.split("|")
                if (parts.size >= 2) {
                    val animeId = parts[0]
                    val episodeNum = parts[1].toIntOrNull() ?: 1
                    
                    // Method 1: Try to get episode servers via AJAX
                    if (tryGetEpisodeViaAjax(animeId, episodeNum, subtitleCallback, callback)) {
                        return true
                    }
                    
                    // Method 2: Try direct episode page
                    if (tryDirectEpisodePage(animeId, episodeNum, subtitleCallback, callback)) {
                        return true
                    }
                }
            }
            
            // Method 3: Try to extract from scripts on the page
            if (tryExtractFromScripts(data, subtitleCallback, callback)) {
                return true
            }
            
            // Method 4: Try iframe (fallback)
            tryIframeFallback(data, subtitleCallback, callback)
            
        } catch (e: Exception) {
            false
        }
    }
    
    private suspend fun tryGetEpisodeViaAjax(
        animeId: String,
        episodeNum: Int,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            // Try to get episode list first
            val episodeListUrl = "$mainUrl/ajax/v2/episode/list/$animeId"
            val episodeListResponse = app.get(episodeListUrl, headers = mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "Referer" to mainUrl
            ))
            
            if (episodeListResponse.isSuccessful) {
                val episodeDoc = episodeListResponse.document
                // Find the specific episode
                val episodeElement = episodeDoc.select("a[data-number=\"$episodeNum\"], a[data-episode=\"$episodeNum\"]").first()
                val episodeId = episodeElement?.attr("data-id") ?: episodeElement?.attr("id")?.removePrefix("episode-")
                
                if (episodeId != null) {
                    // Get servers for this episode
                    val serversUrl = "$mainUrl/ajax/v2/episode/servers?episodeId=$episodeId"
                    val serversResponse = app.get(serversUrl, headers = mapOf(
                        "X-Requested-With" to "XMLHttpRequest",
                        "Referer" to mainUrl
                    ))
                    
                    if (serversResponse.isSuccessful) {
                        val serversDoc = serversResponse.document
                        // Look for server elements with data-link-id
                        val serverElements = serversDoc.select(".server[data-link-id], [data-link-id]")
                        
                        for (server in serverElements) {
                            val dataLinkId = server.attr("data-link-id")
                            if (dataLinkId.isNotBlank()) {
                                // Determine if this is dub or sub
                                val serverText = server.text().lowercase()
                                val isDub = serverText.contains("dub")
                                val language = if (isDub) "dub" else "sub"
                                
                                // Try different server patterns
                                val serverPatterns = listOf("s-1", "s-2", "s-3", "s-4")
                                for (serverNum in serverPatterns) {
                                    val megaUrl = "https://megaplay.buzz/stream/$serverNum/$dataLinkId/$language?autostart=true"
                                    if (loadExtractor(megaUrl, subtitleCallback, callback)) {
                                        return true
                                    }
                                    
                                    // Also try without language
                                    val megaUrlNoLang = "https://megaplay.buzz/stream/$serverNum/$dataLinkId?autostart=true"
                                    if (loadExtractor(megaUrlNoLang, subtitleCallback, callback)) {
                                        return true
                                    }
                                }
                            }
                        }
                    }
                }
            }
            false
        } catch (e: Exception) {
            false
        }
    }
    
    private suspend fun tryDirectEpisodePage(
        animeId: String,
        episodeNum: Int,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            // Try to access the episode page directly
            val episodeUrl = "$mainUrl/watch/$animeId-episode-$episodeNum"
            val document = app.get(episodeUrl).document
            
            // Look for video sources in scripts
            val scripts = document.select("script")
            for (script in scripts) {
                val scriptText = script.html()
                
                // Look for episode ID
                val epIdPattern = Regex("""episodeId['"]?\s*:\s*['"]?(\d+)['"]?""")
                val epIdMatch = epIdPattern.find(scriptText)
                if (epIdMatch != null) {
                    val episodeId = epIdMatch.groupValues[1]
                    
                    // Try different servers
                    val serverPatterns = listOf("s-1", "s-2", "s-3", "s-4")
                    val languages = listOf("sub", "dub")
                    
                    for (serverNum in serverPatterns) {
                        for (language in languages) {
                            val megaUrl = "https://megaplay.buzz/stream/$serverNum/$episodeId/$language?autostart=true"
                            if (loadExtractor(megaUrl, subtitleCallback, callback)) {
                                return true
                            }
                        }
                    }
                }
            }
            
            // Look for iframe
            val iframe = document.selectFirst("iframe[src]")
            val iframeSrc = iframe?.attr("src")?.takeIf { it.isNotBlank() }
                ?.let { if (it.startsWith("http")) it else "https:$it" }
            
            if (iframeSrc != null && loadExtractor(iframeSrc, subtitleCallback, callback)) {
                return true
            }
            
            false
        } catch (e: Exception) {
            false
        }
    }
    
    private suspend fun tryExtractFromScripts(
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            // If data is a URL, try to extract from it
            if (data.startsWith("http")) {
                val document = app.get(data).document
                
                // Look for scripts with video data
                val scripts = document.select("script")
                for (script in scripts) {
                    val scriptText = script.html()
                    
                    // Look for various patterns
                    val patterns = listOf(
                        Regex("""['"]data-link-id['"]\s*:\s*['"]([^'"]+)['"]"""),
                        Regex("""episodeId['"]?\s*:\s*['"]?(\d+)['"]?"""),
                        Regex("""['"]id['"]\s*:\s*['"]?(\d+)['"]?""")
                    )
                    
                    for (pattern in patterns) {
                        val match = pattern.find(scriptText)
                        if (match != null) {
                            val id = match.groupValues[1]
                            
                            // Try different servers
                            val serverPatterns = listOf("s-1", "s-2", "s-3", "s-4")
                            val languages = listOf("sub", "dub")
                            
                            for (serverNum in serverPatterns) {
                                for (language in languages) {
                                    val megaUrl = "https://megaplay.buzz/stream/$serverNum/$id/$language?autostart=true"
                                    if (loadExtractor(megaUrl, subtitleCallback, callback)) {
                                        return true
                                    }
                                }
                            }
                        }
                    }
                }
            }
            false
        } catch (e: Exception) {
            false
        }
    }
    
    private suspend fun tryIframeFallback(
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            // Only try if data is a URL
            if (data.startsWith("http")) {
                val document = app.get(data).document
                
                // Look for iframe
                val iframe = document.selectFirst("iframe[src]")
                val iframeSrc = iframe?.attr("src")?.takeIf { it.isNotBlank() }
                    ?.let { if (it.startsWith("http")) it else "https:$it" }
                
                if (iframeSrc != null && loadExtractor(iframeSrc, subtitleCallback, callback)) {
                    return true
                }
            }
            false
        } catch (e: Exception) {
            false
        }
    }
    
    private fun extractAnimeId(document: org.jsoup.nodes.Document, url: String): String? {
        // Try multiple methods to extract anime ID
        
        // Method 1: From meta tag
        val metaUrl = document.selectFirst("meta[property=og:url]")?.attr("content")
        if (metaUrl != null) {
            val idFromMeta = metaUrl.substringAfterLast("/").substringBefore("?")
            if (idFromMeta.isNotBlank() && idFromMeta != "watch") {
                return idFromMeta
            }
        }
        
        // Method 2: From URL path
        val urlPath = url.removePrefix(mainUrl)
        if (urlPath.isNotBlank()) {
            val segments = urlPath.split("/")
            for (segment in segments) {
                if (segment.isNotBlank() && !segment.contains("?") && 
                    !listOf("anime", "watch", "movie", "tv", "ova").contains(segment.lowercase())) {
                    return segment
                }
            }
        }
        
        // Method 3: Try to find ID in the page
        val idInput = document.selectFirst("input[name=id]")?.attr("value")
        if (idInput != null && idInput.isNotBlank()) {
            return idInput
        }
        
        return null
    }
}
