package com.animesuge.provider

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLEncoder

class AnimeSuge : MainAPI() {
    override var mainUrl = "https://animesuge.bz"
    override var name = "AnimeSuge"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    override val mainPage = mainPageOf(
        "$mainUrl" to "Recently Updated",
        "$mainUrl/new-release" to "Recently Added", 
        "$mainUrl/most-viewed" to "Most Viewed",
        "$mainUrl/status/finished-airing" to "Completed",
        "$mainUrl/status/currently-airing" to "Ongoing"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page > 1) "${request.data}?page=$page" else request.data
        val document = app.get(url).document

        val home = document.select("div.item, a.item").mapNotNull {
            it.toSearchResult()
        }

        return newHomePageResponse(
            list = listOf(HomePageList(request.name, home)),
            hasNext = home.isNotEmpty()
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        // Handle main page items (div.item)
        val titleElement = selectFirst(".name a, .detail .name")
        val title = titleElement?.text()?.trim() ?: return null
        
        val href = when {
            hasAttr("href") -> attr("href") // For <a.item> elements
            else -> selectFirst("a")?.attr("href") // For <div.item> elements
        } ?: return null
        
        val fullUrl = fixUrl(href)
        
        // Extract poster from various possible locations
        val posterUrl = fixUrlNull(
            selectFirst("img.lazyload")?.attr("data-src") ?:
            selectFirst("img")?.attr("src") ?:
            selectFirst(".poster img")?.attr("data-src")
        )
        
        // Determine type from URL or other indicators
        val type = when {
            fullUrl.contains("/movie/") -> TvType.AnimeMovie
            fullUrl.contains("/ova/") -> TvType.OVA
            else -> TvType.Anime
        }

        return newAnimeSearchResponse(title, fullUrl, type) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val document = app.get("$mainUrl/filter?keyword=$encodedQuery").document

        return document.select("div.item").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        // Extract title from multiple possible locations
        val title = document.selectFirst("h1.title, h1.entry-title, h1")?.text()?.trim() ?: return null
        
        // Extract poster
        val poster = fixUrlNull(
            document.selectFirst("img.lazyload")?.attr("data-src") ?:
            document.selectFirst(".poster img")?.attr("src")
        )
        
        // Extract description/synopsis
        val description = document.selectFirst(".description, .story, .synopsis")?.text()?.trim()
        
        // Extract metadata
        val status = document.selectFirst(".meta span:contains(Status) a")?.text()?.trim()
        val year = document.selectFirst(".meta span:contains(Premiered) a")?.text()?.substringAfterLast(" ")?.toIntOrNull()
        val genres = document.select(".meta span:contains(Genre) a").map { it.text().trim() }
        
        // Check if this is an episode page or series page
        val isEpisodePage = url.contains("/ep-")
        
        if (isEpisodePage) {
            // This is an episode page - create single episode
            val episodeNumber = Regex("""/ep-(\d+)""").find(url)?.groupValues?.get(1)?.toIntOrNull() ?: 1
            
            return newMovieLoadResponse(title, url, TvType.Anime, url) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
                this.tags = genres
            }
        } else {
            // This is a series page - extract episodes
            val episodes = mutableListOf<Episode>()
            
            // Get series ID from data attribute
            val seriesId = document.selectFirst("main[data-id]")?.attr("data-id") ?: 
                          Regex("""data-id="(\d+)""").find(document.html())?.groupValues?.get(1)
            
            if (seriesId != null) {
                // Fetch episodes via API
                try {
                    val episodesResponse = app.get("$mainUrl/api/seasons/$seriesId").text
                    if (episodesResponse.isNotBlank() && episodesResponse != "null") {
                        // Parse episodes from the API response
                        episodes.addAll(parseEpisodesFromApi(episodesResponse, url))
                    }
                } catch (e: Exception) {
                    // API failed, try to extract from page
                    episodes.addAll(extractEpisodesFromPage(document, url))
                }
            } else {
                episodes.addAll(extractEpisodesFromPage(document, url))
            }

            return if (episodes.isNotEmpty()) {
                newTvSeriesLoadResponse(title, url, TvType.Anime, episodes.distinctBy { it.episode }.sortedBy { it.episode }) {
                    this.posterUrl = poster
                    this.plot = description
                    this.year = year
                    this.tags = genres
                }
            } else {
                newMovieLoadResponse(title, url, TvType.Anime, url) {
                    this.posterUrl = poster
                    this.plot = description
                    this.year = year
                    this.tags = genres
                }
            }
        }
    }

    private fun parseEpisodesFromApi(apiResponse: String, baseUrl: String): List<Episode> {
        val episodes = mutableListOf<Episode>()
        try {
            // The API returns HTML with episode links
            val doc = Jsoup.parse(apiResponse)
            doc.select("a[href*='/ep-']").forEach { episodeLink ->
                val episodeUrl = fixUrl(episodeLink.attr("href"))
                val episodeText = episodeLink.text().trim()
                val episodeNumber = Regex("""/ep-(\d+)""").find(episodeUrl)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                
                episodes.add(newEpisode(episodeUrl) {
                    this.name = episodeText.ifBlank { "Episode $episodeNumber" }
                    this.episode = episodeNumber
                })
            }
        } catch (e: Exception) {
            // Fallback to regex extraction
            Regex("""href="([^"]*/ep-\d+[^"]*)""").findAll(apiResponse).forEach { match ->
                val episodeUrl = fixUrl(match.groupValues[1])
                val episodeNumber = Regex("""/ep-(\d+)""").find(episodeUrl)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                
                episodes.add(newEpisode(episodeUrl) {
                    this.name = "Episode $episodeNumber"
                    this.episode = episodeNumber
                })
            }
        }
        return episodes
    }

    private fun extractEpisodesFromPage(document: org.jsoup.nodes.Document, baseUrl: String): List<Episode> {
        val episodes = mutableListOf<Episode>()
        
        // Look for episode links in the page
        document.select("a[href*='/ep-']").forEach { episodeLink ->
            val episodeUrl = fixUrl(episodeLink.attr("href"))
            val episodeText = episodeLink.text().trim()
            val episodeNumber = Regex("""/ep-(\d+)""").find(episodeUrl)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            
            episodes.add(newEpisode(episodeUrl) {
                this.name = episodeText.ifBlank { "Episode $episodeNumber" }
                this.episode = episodeNumber
            })
        }
        
        return episodes
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        
        var foundLinks = false

        // Method 1: Look for media servers data
        val mediaServersHtml = document.getElementById("media-servers")?.html()
        if (mediaServersHtml != null) {
            // Extract server data from the media-servers section
            val serverMatches = Regex("""data-id="(\d+)""").findAll(mediaServersHtml)
            serverMatches.forEach { match ->
                val serverId = match.groupValues[1]
                try {
                    val serverUrl = "$mainUrl/ajax/server/$serverId"
                    val response = app.get(serverUrl, referer = data).text
                    
                    // Extract MegaPlay iframe from response
                    val megaplayUrl = extractMegaPlayUrl(response)
                    if (megaplayUrl.isNotBlank()) {
                        foundLinks = true
                        loadExtractor(megaplayUrl, data, subtitleCallback, callback)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        // Method 2: Look for direct MegaPlay iframe in script tags
        if (!foundLinks) {
            document.select("script").forEach { script ->
                val scriptContent = script.html()
                if (scriptContent.contains("megaplay")) {
                    val megaplayUrl = extractMegaPlayUrl(scriptContent)
                    if (megaplayUrl.isNotBlank()) {
                        foundLinks = true
                        loadExtractor(megaplayUrl, data, subtitleCallback, callback)
                    }
                }
            }
        }

        // Method 3: Look for player initialization scripts
        if (!foundLinks) {
            document.select("script[src*='main.js']").forEach { script ->
                // The main.js handles dynamic player loading
                // We need to extract the server data from the page
                val serversData = document.select("[data-id]")
                serversData.forEach { element ->
                    val serverId = element.attr("data-id")
                    if (serverId.isNotBlank()) {
                        try {
                            val serverUrl = "$mainUrl/ajax/server/$serverId"
                            val response = app.get(serverUrl, referer = data).text
                            val megaplayUrl = extractMegaPlayUrl(response)
                            if (megaplayUrl.isNotBlank()) {
                                foundLinks = true
                                loadExtractor(megaplayUrl, data, subtitleCallback, callback)
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
            }
        }

        return foundLinks
    }

    private fun extractMegaPlayUrl(html: String): String {
        // Look for MegaPlay iframe in the response
        val iframeMatch = Regex("""<iframe[^>]*src="([^"]*megaplay[^"]*)""").find(html)
        iframeMatch?.let {
            return it.groupValues[1]
        }
        
        // Look for MegaPlay URL patterns
        val urlMatch = Regex("""(https?://[^\s"']*megaplay[^\s"']*)""").find(html)
        urlMatch?.let {
            return it.value
        }
        
        // Look for base64 encoded URLs
        val base64Match = Regex("""stream/s-1/([^"']+)""").find(html)
        base64Match?.let {
            val encoded = it.groupValues[1]
            return "https://megaplay.buzz/stream/s-1/$encoded"
        }
        
        return ""
    }
}
