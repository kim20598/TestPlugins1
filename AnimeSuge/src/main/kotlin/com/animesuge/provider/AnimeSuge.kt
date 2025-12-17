package com.animesuge.provider

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.net.URLEncoder

class AnimeSuge : MainAPI() {
    override var mainUrl = "https://animesuge.io" // Updated domain
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
        val title = this.selectFirst(".name, .detail .name, .item-bottom .name, span")?.text()?.trim() ?: return null
        val href = fixUrl(this.selectFirst("a[href]")?.attr("href") ?: this.attr("href"))
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
            
            val home = document.select(".anime-card .item, .anime.main-card .item, .anime.mini-card .item, a.item[href*='/watch/']")
                .mapNotNull { it.toSearchResult() }
            
            return newHomePageResponse(request.name, home, hasNext = true)
        } catch (e: Exception) {
            return newHomePageResponse(request.name, emptyList())
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val document = app.get("$mainUrl/filter?keyword=$encodedQuery").document
            
            document.select(".anime-card .item, .anime.main-card .item, .anime.mini-card .item, a.item[href*='/watch/']")
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
            val poster = document.selectFirst("#media-info .poster img, .poster img, [itemprop=image]")?.attr("src")
                ?.let { if (it.startsWith("http")) it else "$mainUrl$it" }
            val plot = document.selectFirst(".description, .plot")?.text()?.trim()
            
            // Extract episodes
            val episodes = mutableListOf<Episode>()
            val rangeContainer = document.selectFirst(".range[data-range]")
            
            if (rangeContainer != null) {
                val episodeLinks = rangeContainer.select("a[href*='/watch/'][href*='/ep-']")
                for (ep in episodeLinks) {
                    try {
                        val episodeUrl = fixUrl(ep.attr("href"))
                        val episodeNumber = ep.attr("data-slug").toIntOrNull() ?: continue
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
                        // Skip episode errors
                    }
                }
            }
            
            // Check if it's a movie
            val isMovie = document.selectFirst(".meta div:contains(Type) + span")
                ?.text()?.contains("movie", true) == true || 
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
            
            // Method 1: Look for server wrapper
            val serverWrapper = document.selectFirst(".server-wrapper")
            if (serverWrapper != null) {
                val servers = serverWrapper.select(".server[data-link-id]")
                for (server in servers) {
                    val dataLinkId = server.attr("data-link-id")
                    if (dataLinkId.isNotBlank()) {
                        // Try Megaplay URL patterns (similar to HiAnime)
                        val serverPatterns = listOf("s-1", "s-2", "s-3", "s-4")
                        for (serverNum in serverPatterns) {
                            val megaUrl = "https://megaplay.buzz/stream/$serverNum/$dataLinkId?autostart=true"
                            if (loadExtractor(megaUrl, subtitleCallback, callback)) {
                                return true
                            }
                        }
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
            
            false
        } catch (e: Exception) {
            false
        }
    }
}
