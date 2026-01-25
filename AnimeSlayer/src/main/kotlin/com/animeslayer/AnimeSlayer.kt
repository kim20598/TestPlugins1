package com.animeslayer

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLEncoder

class AnimeSlayer : MainAPI() {
    override var mainUrl = "https://animeslayerweb.com"
    override var name = "AnimeSlayer"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "أحدث الحلقات",
        "$mainUrl/anime/?status=ongoing" to "الأنمي المستمر",
        "$mainUrl/anime/?status=completed&order=rating" to "الأعلى تقييماً",
        "$mainUrl/anime/?status=completed" to "الأنمي المكتمل"
    )

    // Parse anime cards
    private fun Element.toSearchResult(): SearchResponse? {
        val href = this.selectFirst("a")?.attr("href") ?: return null
        val title = this.selectFirst(".tt h2, .tt, h2")?.text()?.trim() 
            ?: this.selectFirst("img")?.attr("alt")?.trim()
            ?: return null
            
        val poster = this.selectFirst("img")?.attr("src")?.let { fixUrl(it) }
        
        return newAnimeSearchResponse(title, fixUrl(href)) {
            this.posterUrl = poster
        }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data}page/$page/"
        val doc = app.get(url).document
        
        val items = doc.select("article.bs, .bsx").mapNotNull { it.toSearchResult() }
        
        return newHomePageResponse(request.name, items, items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val doc = app.get("$mainUrl/?s=$encoded").document
        
        return doc.select("article.bs, .bsx, article").mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        
        // Title
        val title = doc.selectFirst("h1.entry-title")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: "Unknown"
        
        // Poster
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")?.let { fixUrl(it) }
            ?: doc.selectFirst(".thumb img, .thumbook img")?.attr("src")?.let { fixUrl(it) }
        
        // Plot
        val plot = doc.selectFirst(".desc, .entry-content")?.text()?.trim()
            ?: doc.selectFirst("meta[name=description]")?.attr("content")?.trim()
        
        // Check if it's a movie or series
        val isMovie = doc.selectFirst("span:contains(النوع:)")?.text()?.contains("فيلم") == true
            || doc.selectFirst(".typez.Movie") != null
        
        // Extract episodes
        val episodes = mutableListOf<Episode>()
        val episodeElements = doc.select("#EpList1 .CSB")
        
        if (episodeElements.isNotEmpty() && !isMovie) {
            episodeElements.forEachIndexed { index, element ->
                val episodeNum = index + 1
                val episodeName = element.text().trim()
                
                episodes.add(
                    newEpisode("$url?ep=$episodeNum") {
                        this.name = episodeName
                        this.episode = episodeNum
                        this.season = 1
                    }
                )
            }
        } else {
            // Try to get episode count from info
            val episodeInfo = doc.select("span:contains(الحلقات:)").firstOrNull()
            val epCount = episodeInfo?.text()?.let {
                Regex("""\d+""").find(it)?.value?.toIntOrNull()
            }
            
            if (epCount != null && epCount > 0 && !isMovie) {
                for (i in 1..epCount) {
                    episodes.add(
                        newEpisode("$url?ep=$i") {
                            this.name = "الحلقة $i"
                            this.episode = i
                            this.season = 1
                        }
                    )
                }
            }
        }
        
        // Return appropriate response
        return if (isMovie || episodes.isEmpty()) {
            newMovieLoadResponse(title, url, TvType.AnimeMovie, url) {
                this.posterUrl = poster
                this.plot = plot
            }
        } else {
            newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
                this.posterUrl = poster
                this.plot = plot
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
            val url = data.substringBefore("?ep=")
            val episode = data.substringAfter("?ep=").toIntOrNull() ?: 1
            
            val doc = app.get(url).document
            
            // Get server containers
            val serverContainers = doc.select(".divv11")
            if (serverContainers.size >= episode) {
                val container = serverContainers[episode - 1]
                val servers = container.select(".ul-server-position1 li")
                
                servers.forEach { server ->
                    val dataValue = server.attr("data-url").ifBlank { server.attr("data") }
                    if (dataValue.isNotBlank()) {
                        val serverName = server.text().trim()
                        val quality = when {
                            server.attr("quality-data").contains("FHD") -> Qualities.P1080.value
                            server.attr("quality-data").contains("HD") -> Qualities.P720.value
                            server.attr("quality-data").contains("SD") -> Qualities.P480.value
                            else -> Qualities.Unknown.value
                        }
                        
                        val serverType = server.attr("type") ?: server.attr("class")
                        
                        when {
                            serverType.contains("vanfem") -> {
                                val vanfemUrl = "https://vanfem.com/e/$dataValue"
                                callback.invoke(
                                    newExtractorLink(
                                        source = this.name,
                                        name = serverName,
                                        url = vanfemUrl,
                                        referer = this.mainUrl,
                                        quality = quality,
                                        isM3u8 = false
                                    )
                                )
                                return true
                            }
                            serverType.contains("mega") -> {
                                val megaUrl = "https://mega.nz/file/$dataValue"
                                callback.invoke(
                                    newExtractorLink(
                                        source = this.name,
                                        name = serverName,
                                        url = megaUrl,
                                        referer = this.mainUrl,
                                        quality = quality,
                                        isM3u8 = false
                                    )
                                )
                                return true
                            }
                            serverType.contains("drive") -> {
                                val driveUrl = "https://drive.google.com/file/d/$dataValue/view"
                                callback.invoke(
                                    newExtractorLink(
                                        source = this.name,
                                        name = serverName,
                                        url = driveUrl,
                                        referer = this.mainUrl,
                                        quality = quality,
                                        isM3u8 = false
                                    )
                                )
                                return true
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
    
    private fun fixUrl(url: String): String {
        return when {
            url.startsWith("http") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "$mainUrl$url"
            else -> "$mainUrl/$url"
        }
    }
}
