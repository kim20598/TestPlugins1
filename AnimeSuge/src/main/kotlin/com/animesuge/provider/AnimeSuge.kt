package com.animesuge.provider

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.*
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
        val title = document.selectFirst("h1.entry-title, h1.title, h1")?.text()?.trim() ?: return null
        
        // Extract poster
        val poster = fixUrlNull(
            document.selectFirst("img.lazyload")?.attr("data-src") ?:
            document.selectFirst(".poster img")?.attr("src")
        )
        
        // Extract description/synopsis
        val description = document.selectFirst(".story, .synopsis, .description, .plot")?.text()?.trim()
        
        // Extract metadata
        val metaElements = document.select(".meta span, .dub-sub-total span")
        val subCount = metaElements.find { it.text().contains("sub", true) }?.text()?.filter { it.isDigit() }?.toIntOrNull()
        val dubCount = metaElements.find { it.text().contains("dub", true) }?.text()?.filter { it.isDigit() }?.toIntOrNull()
        
        // Extract episodes - look for episode links
        val episodes = mutableListOf<Episode>()
        
        // Method 1: Look for episode links in the main content
        document.select("a[href*='/watch/']").forEach { episodeLink ->
            val episodeUrl = fixUrl(episodeLink.attr("href"))
            if (episodeUrl.contains("/ep-")) {
                val episodeText = episodeLink.text().trim()
                
                // Extract episode number from URL pattern /ep-XXX
                val episodeNumber = Regex("""/ep-(\d+)""").find(episodeUrl)?.groupValues?.get(1)?.toIntOrNull() ?:
                                  Regex("""\b(\d+)\b""").find(episodeText)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                
                episodes.add(newEpisode(episodeUrl) {
                    this.name = episodeText.ifBlank { "Episode $episodeNumber" }
                    this.episode = episodeNumber
                })
            }
        }

        // Method 2: If no episodes found, check if this is a direct watch page
        if (episodes.isEmpty() && url.contains("/watch/")) {
            // This is already an episode page, add it as a single episode
            val episodeNumber = Regex("""/ep-(\d+)""").find(url)?.groupValues?.get(1)?.toIntOrNull() ?: 1
            episodes.add(newEpisode(url) {
                this.name = "Episode $episodeNumber"
                this.episode = episodeNumber
            })
        }

        // Determine type
        val type = when {
            url.contains("/movie/") -> TvType.AnimeMovie
            url.contains("/ova/") -> TvType.OVA
            else -> TvType.Anime
        }

        return if (episodes.isEmpty() || episodes.size == 1) {
            // Movie or single episode
            newMovieLoadResponse(title, url, type, url) {
                this.posterUrl = poster
                this.plot = description
            }
        } else {
            // TV Series with episodes
            newTvSeriesLoadResponse(title, url, type, episodes.distinctBy { it.episode }.sortedBy { it.episode }) {
                this.posterUrl = poster
                this.plot = description
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        
        var foundLinks = false

        // Method 1: Look for MegaPlay iframe embeds (primary method)
        document.select("iframe[src*='megaplay.buzz']").forEach { iframe ->
            val iframeSrc = iframe.attr("src")
            if (iframeSrc.isNotBlank()) {
                foundLinks = true
                // MegaPlay extractor should handle this URL
                loadExtractor(fixUrl(iframeSrc), data, subtitleCallback, callback)
            }
        }

        // Method 2: Look for server buttons with data attributes
        document.select("button[data-id], div[data-id]").forEach { element ->
            val serverId = element.attr("data-id")
            if (serverId.isNotBlank()) {
                try {
                    val loadUrl = "$mainUrl/ajax/server/$serverId"
                    val response = app.get(loadUrl, referer = data).text
                    
                    // Extract MegaPlay URLs from AJAX response
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

        // Method 3: Look for direct video players
        if (!foundLinks) {
            document.select("div#player, div.video-player").forEach { player ->
                player.select("iframe[src]").forEach { iframe ->
                    val iframeSrc = iframe.attr("src")
                    if (iframeSrc.isNotBlank() && iframeSrc.contains("megaplay")) {
                        foundLinks = true
                        loadExtractor(fixUrl(iframeSrc), data, subtitleCallback, callback)
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
        
        return ""
    }
}
