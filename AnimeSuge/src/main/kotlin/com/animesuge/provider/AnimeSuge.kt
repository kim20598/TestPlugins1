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
        "$mainUrl" to "Latest Episodes",
        "$mainUrl/filter?type%5B%5D=tv&status%5B%5D=completed&status%5B%5D=upcoming&status%5B%5D=releasing&order=latest" to "Latest Anime",
        "$mainUrl/filter?type%5B%5D=movie&status%5B%5D=completed&status%5B%5D=upcoming&status%5B%5D=releasing&order=latest" to "Movies",
        "$mainUrl/filter?type%5B%5D=ova&status%5B%5D=completed&status%5B%5D=upcoming&status%5B%5D=releasing&order=latest" to "OVA",
        "$mainUrl/filter?type%5B%5D=tv&status%5B%5D=completed&status%5B%5D=upcoming&status%5B%5D=releasing&order=popular" to "Popular Anime"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = if (page == 1) {
            app.get(request.data).document
        } else {
            val paginatedUrl = if (request.data.contains("?")) {
                "${request.data}&page=$page"
            } else {
                "${request.data}?page=$page"
            }
            app.get(paginatedUrl).document
        }

        val home = document.select("div.item, div.anime-card, div.card").mapNotNull {
            it.toSearchResult()
        }

        return newHomePageResponse(
            list = listOf(HomePageList(request.name, home)),
            hasNext = home.isNotEmpty()
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleElement = selectFirst("div.name a, .anime-title, .title a, h3 a")
        val title = titleElement?.text()?.trim() ?: return null
        
        val href = fixUrl(titleElement.attr("href") ?: return null)
        val posterElement = selectFirst("img, .poster img, .anime-poster img")
        val posterUrl = fixUrlNull(posterElement?.attr("src") ?: posterElement?.attr("data-src"))
        
        val type = when {
            href.contains("/movie/") -> TvType.AnimeMovie
            href.contains("/ova/") -> TvType.OVA
            else -> TvType.Anime
        }

        return newAnimeSearchResponse(title, href, type) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val document = app.get("$mainUrl/filter?keyword=$encodedQuery").document

        return document.select("div.item, div.anime-card, div.card").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h1.title, h1.anime-title, h1")?.text()?.trim() ?: return null
        val poster = fixUrlNull(document.selectFirst("div.poster img, .anime-poster img, .poster img")?.attr("src"))
        val description = document.selectFirst("div.description, .synopsis, .plot")?.text()?.trim()
        
        // Try to extract year from various possible locations
        val yearText = document.select("div.meta:contains(Released), div.meta:contains(Year), .year, .release-date")
            .firstOrNull()?.text()
        val year = yearText?.let { 
            Regex("\\d{4}").find(it)?.value?.toIntOrNull() 
        }
        
        val statusText = document.select("div.meta:contains(Status), .status, .anime-status")
            .firstOrNull()?.text()?.trim()
        val status = when (statusText?.lowercase()) {
            "completed", "finished" -> ShowStatus.Completed
            "ongoing", "releasing", "airing" -> ShowStatus.Ongoing
            else -> ShowStatus.Completed
        }

        val genres = document.select("div.genres a, .genre a, .tags a").map { it.text().trim() }
        
        // Extract episodes - try multiple possible selectors
        val episodes = document.select("div.episodes a, .episode-list a, .episode-item a, a.episode").mapNotNull { episode ->
            val episodeTitle = episode.select("span.name, .episode-title, .title").text().trim()
            val episodeNumber = episode.attr("data-number").toIntOrNull() ?: 
                Regex("Episode\\s*(\\d+)").find(episodeTitle)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val episodeUrl = fixUrl(episode.attr("href"))
            
            if (episodeUrl.isNotBlank()) {
                newEpisode(episodeUrl) {
                    this.name = if (episodeTitle.isNotBlank()) episodeTitle else "Episode $episodeNumber"
                    this.episode = episodeNumber
                }
            } else {
                null
            }
        }.reversed()

        val type = when {
            url.contains("/movie/") -> TvType.AnimeMovie
            url.contains("/ova/") -> TvType.OVA
            else -> TvType.Anime
        }

        return newAnimeLoadResponse(title, url, type) {
            this.posterUrl = poster
            this.plot = description
            this.year = year
            this.showStatus = status
            this.tags = genres
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document

        // Extract video servers - try multiple possible selectors
        val servers = document.select("""
            div.server[data-server], 
            div.server[data-id],
            .server-option,
            .video-server,
            [data-server]
        """.trimIndent())
        
        servers.forEach { server ->
            val serverName = server.attr("data-server").takeIf { it.isNotBlank() } 
                ?: server.attr("data-name")
                ?: server.text().trim()
                ?: "Server"
            
            val serverId = server.attr("data-id").takeIf { it.isNotBlank() }
                ?: server.attr("data-server-id")
                ?: server.id()
            
            if (serverId.isNotBlank()) {
                try {
                    val loadUrl = "$mainUrl/ajax/server/$serverId"
                    val response = app.get(loadUrl, referer = data).text
                    
                    // Try multiple patterns to find iframe source
                    val iframeSrc = Regex("""<iframe\s+[^>]*src="([^"]+)""").find(response)?.groupValues?.get(1)
                        ?: Regex("""src\s*=\s*["']([^"']+)""").find(response)?.groupValues?.get(1)
                        ?: Regex("""(https?://[^\s"']+\.m3u8[^\s"']*)""").find(response)?.groupValues?.get(1)
                    
                    iframeSrc?.let { src ->
                        val fixedSrc = when {
                            src.startsWith("//") -> "https:$src"
                            src.startsWith("/") -> "$mainUrl$src"
                            else -> src
                        }
                        
                        // Load extractor for the iframe source
                        loadExtractor(fixedSrc, data, subtitleCallback, callback)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        return servers.isNotEmpty()
    }
}