package com.animesuge.provider

import com.fasterxml.jackson.annotation.JsonProperty
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
            
            // Check if it's a movie by looking at the Type in meta
            val typeText = document.selectFirst(".meta div:contains(Type) + span")?.text()?.trim()
            val isMovie = typeText?.contains("movie", true) == true || 
                         typeText?.contains("movie", true) == true ||
                         url.contains("/movie/")
            
            // Get episodes - try multiple methods
            val episodes = mutableListOf<Episode>()
            
            if (!isMovie) {
                // Method 1: Look for episode links in the page
                val episodeLinks = document.select("a[href*='/watch/'][href*='/ep-']")
                if (episodeLinks.isNotEmpty()) {
                    episodeLinks.forEach { ep ->
                        try {
                            val episodeUrl = fixUrl(ep.attr("href"))
                            val episodeNumber = ep.attr("data-slug").toIntOrNull()
                                ?: ep.text().trim().filter { it.isDigit() }.toIntOrNull()
                                ?: return@forEach
                            
                            val episodeName = ep.attr("data-num").takeIf { it.isNotBlank() }
                                ?: ep.attr("title").takeIf { it.isNotBlank() }
                                ?: "Episode $episodeNumber"
                            
                            episodes.add(
                                newEpisode(episodeUrl) {
                                    name = episodeName
                                    this.episode = episodeNumber
                                }
                            )
                        } catch (e: Exception) {
                            // Skip episode
                        }
                    }
                } else {
                    // Method 2: Check the dub-sub-total count
                    val subCount = document.selectFirst(".dub-sub-total .sub")?.text()?.toIntOrNull() ?: 0
                    val dubCount = document.selectFirst(".dub-sub-total .dub")?.text()?.toIntOrNull() ?: 0
                    val totalCount = document.selectFirst(".dub-sub-total .total")?.text()?.toIntOrNull() ?: 0
                    
                    val episodeCount = maxOf(subCount, dubCount, totalCount)
                    
                    if (episodeCount > 0) {
                        // Generate episode URLs based on the URL pattern
                        val baseUrl = url.substringBeforeLast("/ep-").substringBeforeLast("/")
                        for (i in 1..episodeCount) {
                            val episodeUrl = "$baseUrl/ep-$i"
                            episodes.add(
                                newEpisode(episodeUrl) {
                                    name = "Episode $i"
                                    this.episode = i
                                }
                            )
                        }
                    } else {
                        // Method 3: Check meta for episode count
                        val metaEpisodes = document.select(".meta div:contains(Episodes:) + span")?.text()?.toIntOrNull() ?: 0
                        if (metaEpisodes > 0) {
                            val baseUrl = url.substringBeforeLast("/")
                            for (i in 1..metaEpisodes) {
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
            }
            
            if (isMovie || episodes.isEmpty()) {
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
            
            // Method 1: Look for server wrapper with data-link-id
            val servers = document.select(".server[data-link-id], [data-link-id]")
            for (server in servers) {
                val dataLinkId = server.attr("data-link-id")
                if (dataLinkId.isNotBlank()) {
                    // AnimeSuge uses MegaPlay - try different server patterns
                    val serverPatterns = listOf("s-1", "s-2", "s-3", "s-4")
                    for (serverNum in serverPatterns) {
                        val megaUrl = "https://megaplay.buzz/stream/$serverNum/$dataLinkId?autostart=true"
                        if (loadExtractor(megaUrl, subtitleCallback, callback)) {
                            return true
                        }
                    }
                    
                    // Try direct MegaPlay API
                    val megaApiUrl = "https://megaplay.buzz/stream/getSources?id=$dataLinkId"
                    if (loadExtractor(megaApiUrl, subtitleCallback, callback)) {
                        return true
                    }
                }
            }
            
            // Method 2: Look for iframe
            val iframe = document.selectFirst("iframe[src]")
            val iframeSrc = iframe?.attr("src")?.takeIf { it.isNotBlank() }
                ?.let { if (it.startsWith("http")) it else "https:$it" }
            
            if (iframeSrc != null && loadExtractor(iframeSrc, subtitleCallback, callback)) {
                return true
            }
            
            // Method 3: Look for video player scripts
            val scripts = document.select("script")
            for (script in scripts) {
                val scriptText = script.html()
                
                // Look for MegaPlay pattern
                val megaPattern = Regex("""megaplay\.buzz/stream/([^"'\s?]+)""")
                val matches = megaPattern.findAll(scriptText)
                
                for (match in matches) {
                    val megaUrl = "https://${match.value}"
                    if (loadExtractor(megaUrl, subtitleCallback, callback)) {
                        return true
                    }
                }
                
                // Look for data-link-id in scripts
                val linkIdPattern = Regex("""data-link-id['"]?\s*:\s*['"]([^'"]+)['"]""")
                val linkIdMatch = linkIdPattern.find(scriptText)
                if (linkIdMatch != null) {
                    val dataLinkId = linkIdMatch.groupValues[1]
                    val megaUrl = "https://megaplay.buzz/stream/s-1/$dataLinkId?autostart=true"
                    if (loadExtractor(megaUrl, subtitleCallback, callback)) {
                        return true
                    }
                }
            }
            
            false
        } catch (e: Exception) {
            false
        }
    }
}
