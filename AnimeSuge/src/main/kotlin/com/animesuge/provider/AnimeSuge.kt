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
            
            // Try different selectors
            val selectors = listOf(
                ".item",
                ".anime-card",
                ".card",
                ".anime-poster",
                ".poster",
                "article",
                "a[href*='/watch/']:has(img)"
            )
            
            val home = mutableListOf<SearchResponse>()
            
            for (selector in selectors) {
                val items = document.select(selector)
                if (items.isNotEmpty()) {
                    home.addAll(items.mapNotNull { it.toSearchResult() })
                    break
                }
            }
            
            return newHomePageResponse(request.name, home.distinctBy { it.url }, hasNext = true)
        } catch (e: Exception) {
            e.printStackTrace()
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
            
            // Extract anime ID from the page
            val dataId = document.selectFirst("[data-id]")?.attr("data-id")?.toIntOrNull()
            val isMovie = document.selectFirst(".meta div:contains(Type) + span")
                ?.text()?.contains("movie", true) == true
            
            var episodes: List<Episode> = emptyList()
            
            // Try to get episodes via API
            if (!isMovie && dataId != null) {
                episodes = try {
                    // Try to get episodes from API
                    val apiUrl = "$mainUrl/api/seasons/$dataId"
                    val apiResponse = app.get(apiUrl).parsedSafe<ApiResponse>()
                    
                    if (apiResponse?.status == 200 && apiResponse.result?.isNotBlank() == true) {
                        // Parse HTML from API response
                        val episodesDoc = app.parse(apiResponse.result)
                        episodesDoc.select("a[href*='/watch/'][href*='/ep-']").mapNotNull { ep ->
                            try {
                                val episodeUrl = fixUrl(ep.attr("href"))
                                val episodeNumber = ep.attr("data-slug").toIntOrNull()
                                    ?: ep.text().trim().filter { it.isDigit() }.toIntOrNull()
                                    ?: return@mapNotNull null
                                
                                val episodeName = ep.attr("data-num").takeIf { it.isNotBlank() }
                                    ?: ep.attr("title").takeIf { it.isNotBlank() }
                                    ?: "Episode $episodeNumber"
                                
                                newEpisode(episodeUrl) {
                                    name = episodeName
                                    this.episode = episodeNumber
                                }
                            } catch (e: Exception) {
                                null
                            }
                        }
                    } else {
                        // Fallback: try to find episodes in the page
                        document.select("a[href*='/watch/'][href*='/ep-']").mapNotNull { ep ->
                            try {
                                val episodeUrl = fixUrl(ep.attr("href"))
                                val episodeNumber = ep.attr("data-slug").toIntOrNull()
                                    ?: ep.text().trim().filter { it.isDigit() }.toIntOrNull()
                                    ?: return@mapNotNull null
                                
                                val episodeName = ep.attr("data-num").takeIf { it.isNotBlank() }
                                    ?: ep.attr("title").takeIf { it.isNotBlank() }
                                    ?: "Episode $episodeNumber"
                                
                                newEpisode(episodeUrl) {
                                    name = episodeName
                                    this.episode = episodeNumber
                                }
                            } catch (e: Exception) {
                                null
                            }
                        }
                    }
                } catch (e: Exception) {
                    emptyList()
                }
            }
            
            // Determine if it's a movie or series
            val type = if (isMovie || episodes.isEmpty()) {
                TvType.AnimeMovie
            } else {
                TvType.Anime
            }
            
            if (type == TvType.AnimeMovie) {
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
            e.printStackTrace()
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
            
            // Try to extract server data from the page
            val serversScript = document.select("script").find { script ->
                script.html().contains("data-link-id")
            }
            
            if (serversScript != null) {
                val scriptText = serversScript.html()
                // Look for data-link-id pattern
                val linkIdRegex = Regex("data-link-id=['\"]([^'\"]+)['\"]")
                val match = linkIdRegex.find(scriptText)
                
                if (match != null) {
                    val dataLinkId = match.groupValues[1]
                    // Try different server patterns
                    val serverPatterns = listOf("s-1", "s-2", "s-3", "s-4")
                    for (serverNum in serverPatterns) {
                        val megaUrl = "https://megaplay.buzz/stream/$serverNum/$dataLinkId?autostart=true"
                        if (loadExtractor(megaUrl, subtitleCallback, callback)) {
                            return true
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
            e.printStackTrace()
            false
        }
    }
    
    data class ApiResponse(
        @JsonProperty("status") val status: Int? = null,
        @JsonProperty("result") val result: String? = null
    )
}
