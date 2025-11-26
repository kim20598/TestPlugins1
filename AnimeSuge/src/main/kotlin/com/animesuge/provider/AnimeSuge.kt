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
        "$mainUrl" to "Latest Anime",
        "$mainUrl/filter?type[]=tv&sort=default" to "TV Series",
        "$mainUrl/filter?type[]=movie&sort=default" to "Movies",
        "$mainUrl/filter?type[]=ova&sort=default" to "OVA",
        "$mainUrl/filter?sort=views" to "Most Viewed"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data}&page=$page"
        val document = app.get(url).document

        val home = document.select("div.anime").mapNotNull {
            it.toSearchResult()
        }

        return newHomePageResponse(
            list = listOf(HomePageList(request.name, home)),
            hasNext = home.isNotEmpty()
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleElement = selectFirst("div.name a")
        val title = titleElement?.text()?.trim() ?: return null
        
        val href = fixUrl(titleElement.attr("href"))
        val posterUrl = fixUrlNull(selectFirst("img")?.attr("src"))
        
        // Determine type from URL or other indicators
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

        return document.select("div.anime").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        // Extract title
        val title = document.selectFirst("h1.title")?.text()?.trim() ?: return null
        
        // Extract poster
        val poster = fixUrlNull(document.selectFirst("div.poster img")?.attr("src"))
        
        // Extract description
        val description = document.selectFirst("div.story")?.text()?.trim()
        
        // Extract year
        val yearText = document.select("div.meta:contains(Year) span.value").text()
        val year = yearText.toIntOrNull()
        
        // Extract status
        val statusText = document.select("div.meta:contains(Status) span.value").text().lowercase()
        val status = when {
            statusText.contains("completed") -> ShowStatus.Completed
            statusText.contains("ongoing") -> ShowStatus.Ongoing
            else -> ShowStatus.Completed
        }

        // Extract genres
        val genres = document.select("div.genres a").map { it.text().trim() }
        
        // Extract episodes
        val episodes = document.select("div.episodes a").mapNotNull { episode ->
            val episodeUrl = fixUrl(episode.attr("href"))
            val episodeTitle = episode.select("span.name").text().trim()
            val episodeNumber = episode.attr("data-number").toIntOrNull() ?: 0
            
            newEpisode(episodeUrl) {
                this.name = episodeTitle.ifBlank { "Episode $episodeNumber" }
                this.episode = episodeNumber
            }
        }.reversed()

        // Determine type
        val type = when {
            url.contains("/movie/") -> TvType.AnimeMovie
            url.contains("/ova/") -> TvType.OVA
            else -> TvType.Anime
        }

        return if (episodes.isEmpty()) {
            // Movie
            newMovieLoadResponse(title, url, type, url) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
                this.showStatus = status
                this.tags = genres
            }
        } else {
            // TV Series
            newTvSeriesLoadResponse(title, url, type, episodes) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
                this.showStatus = status
                this.tags = genres
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

        // Method 1: Direct server links with data-id
        document.select("div.server[data-id]").forEach { server ->
            val serverId = server.attr("data-id")
            if (serverId.isNotBlank()) {
                try {
                    val loadUrl = "$mainUrl/ajax/server/$serverId"
                    val response = app.get(loadUrl, referer = data).text
                    
                    // Extract iframe src from response
                    val iframeMatch = Regex("""<iframe[^>]*src="([^"]+)""").find(response)
                    val iframeSrc = iframeMatch?.groupValues?.get(1)
                    
                    iframeSrc?.let { src ->
                        val fixedSrc = if (src.startsWith("//")) "https:$src" else src
                        foundLinks = true
                        loadExtractor(fixedSrc, data, subtitleCallback, callback)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        // Method 2: Direct video links in the page
        if (!foundLinks) {
            document.select("source[src]").forEach { source ->
                val videoUrl = source.attr("src")
                if (videoUrl.isNotBlank()) {
                    foundLinks = true
                    callback(
                        newExtractorLink(
                            name,
                            name,
                            videoUrl,
                            "",
                            getQualityFromName(source.attr("label")),
                            false
                        )
                    )
                }
            }
        }

        return foundLinks
    }
}
