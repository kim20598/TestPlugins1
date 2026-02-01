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
        TvType.OVA,
        TvType.ONA
    )

    override val mainPage = mainPageOf(
        "$mainUrl/animeslayer/" to "الصفحة الرئيسية",
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
        
        // Get type from the div
        val type = when {
            this.selectFirst(".typez.TV") != null -> TvType.Anime
            this.selectFirst(".typez.Movie") != null -> TvType.AnimeMovie
            this.selectFirst(".typez.OVA") != null -> TvType.OVA
            this.selectFirst(".typez.ONA") != null -> TvType.ONA
            else -> TvType.Anime
        }
        
        return newAnimeSearchResponse(title, fixUrl(href)) {
            this.posterUrl = poster
            this.type = type
        }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data}page/$page/"
        val doc = app.get(url).document
        
        val items = doc.select("article.bs, .bsx, .listupd article").mapNotNull { it.toSearchResult() }
        
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
        
        // Remove suffix from title
        val cleanTitle = title.replace(" - Anime Slayer Web | موقع انمي سلاير ويب", "")
            .replace("Anime Slayer Web | موقع انمي سلاير ويب", "")
            .trim()
        
        // Poster
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")?.let { fixUrl(it) }
            ?: doc.selectFirst(".thumb img, .thumbook img")?.attr("src")?.let { fixUrl(it) }
        
        // Plot
        val plot = doc.selectFirst(".desc, .entry-content")?.text()?.trim()
            ?: doc.selectFirst("meta[name=description]")?.attr("content")?.trim()
        
        // Check if it's a movie or series
        val isMovie = doc.selectFirst("span:contains(النوع:)")?.text()?.contains("فيلم") == true
            || doc.selectFirst(".typez.Movie") != null
            || doc.selectFirst("b:contains(النوع:)")?.nextElementSibling()?.text()?.contains("فيلم") == true
        
        // Extract episodes from the noscript element (more reliable)
        val episodes = mutableListOf<Episode>()
        
        // First check in the noscript element
        val noscriptElement = doc.selectFirst("noscript#diplayer")
        if (noscriptElement != null) {
            val noscriptHtml = noscriptElement.html()
            val noscriptDoc = org.jsoup.Jsoup.parse(noscriptHtml)
            
            val episodeElements = noscriptDoc.select("#EpList1 .CSB")
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
            }
        }
        
        // If no episodes found in noscript, try regular way
        if (episodes.isEmpty()) {
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
                // Check episode count from the eplister section
                val eplisterItems = doc.select(".eplister ul li")
                if (eplisterItems.isNotEmpty() && !isMovie) {
                    eplisterItems.forEach { item ->
                        val episodeNumText = item.selectFirst(".eph-num")?.text()?.trim()
                        val episodeNum = episodeNumText?.filter { it.isDigit() }?.toIntOrNull()
                        val episodeTitle = item.selectFirst(".eph-title")?.text()?.trim()
                        
                        if (episodeNum != null) {
                            episodes.add(
                                newEpisode("$url?ep=$episodeNum") {
                                    this.name = episodeTitle ?: "الحلقة $episodeNum"
                                    this.episode = episodeNum
                                    this.season = 1
                                }
                            )
                        }
                    }
                }
            }
        }
        
        // Sort episodes by number
        episodes.sortBy { it.episode }
        
        // Return appropriate response
        return if (isMovie || episodes.isEmpty()) {
            newMovieLoadResponse(cleanTitle, url, TvType.AnimeMovie, url) {
                this.posterUrl = poster
                this.plot = plot
                this.year = doc.selectFirst("span.split:contains(تم الإصدار:)")?.text()?.filter { it.isDigit() }?.take(4)?.toIntOrNull()
            }
        } else {
            newTvSeriesLoadResponse(cleanTitle, url, TvType.Anime, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.year = doc.selectFirst("span.split:contains(تم الإصدار:)")?.text()?.filter { it.isDigit() }?.take(4)?.toIntOrNull()
                this.tags = doc.select(".genxed a").map { it.text().trim() }
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
            
            // First try to get links from the noscript element (the actual video links)
            val noscriptElement = doc.selectFirst("noscript#diplayer")
            if (noscriptElement != null) {
                // Parse the HTML inside the noscript tag
                val noscriptHtml = noscriptElement.html()
                val noscriptDoc = org.jsoup.Jsoup.parse(noscriptHtml)
                
                // Get all divv11 containers (each contains servers for one episode)
                val serverContainers = noscriptDoc.select(".divv11")
                if (serverContainers.isNotEmpty() && serverContainers.size >= episode) {
                    val container = serverContainers[episode - 1]
                    val servers = container.select(".ul-server-position1 li")
                    
                    // Try each server in order
                    servers.forEach { server ->
                        val dataValue = server.attr("data")?.trim()
                        val serverType = server.attr("type")?.lowercase() ?: server.attr("class")?.lowercase() ?: ""
                        val quality = server.attr("quality-data") ?: "HD"
                        
                        if (!dataValue.isNullOrBlank()) {
                            val extractedUrl = when {
                                serverType.contains("ok") -> {
                                    // OK.ru links
                                    if (dataValue.matches(Regex("\\d+"))) {
                                        "https://ok.ru/video/$dataValue"
                                    } else {
                                        null
                                    }
                                }
                                serverType.contains("mega") -> {
                                    // Mega.nz links
                                    if (dataValue.contains("#")) {
                                        val parts = dataValue.split("#")
                                        if (parts.size >= 2) {
                                            "https://mega.nz/file/${parts[0]}#${parts[1]}"
                                        } else {
                                            "https://mega.nz/file/$dataValue"
                                        }
                                    } else {
                                        "https://mega.nz/file/$dataValue"
                                    }
                                }
                                serverType.contains("videa") -> {
                                    // Videa links
                                    "https://videa.hu/videok/$dataValue"
                                }
                                serverType.contains("mp4upload") -> {
                                    // MP4Upload links
                                    "https://mp4upload.com/embed-$dataValue.html"
                                }
                                serverType.contains("uqload") -> {
                                    // UQLoad links
                                    "https://uqload.com/embed-$dataValue.html"
                                }
                                serverType.contains("dailymotion") -> {
                                    // Dailymotion links
                                    "https://www.dailymotion.com/embed/video/$dataValue"
                                }
                                serverType.contains("4shared") -> {
                                    // 4shared links
                                    "https://www.4shared.com/video/$dataValue"
                                }
                                serverType.contains("asnwish") -> {
                                    // ASNWISH links
                                    "https://asnwish.com/e/$dataValue"
                                }
                                else -> null
                            }
                            
                            if (extractedUrl != null) {
                                // Create quality label
                                val qualityLabel = when (quality) {
                                    "FHD" -> Qualities.FullHDP.value
                                    "HD" -> Qualities.HDP.value
                                    "SD" -> Qualities.SD.value
                                    "LD" -> Qualities.LD.value
                                    else -> Qualities.HDP.value
                                }
                                
                                // Get server name for label
                                val serverName = server.text().trim()
                                
                                if (loadExtractor(extractedUrl, "$mainUrl/", subtitleCallback, callback)) {
                                    return true
                                }
                            }
                        }
                    }
                }
            }
            
            // If not found in noscript, try the regular way (fallback)
            val serverContainers = doc.select(".divv11")
            if (serverContainers.isNotEmpty() && serverContainers.size >= episode) {
                val container = serverContainers[episode - 1]
                val servers = container.select(".ul-server-position1 li")
                
                servers.forEach { server ->
                    val dataValue = server.attr("data")?.trim()
                    val serverType = server.attr("type")?.lowercase() ?: server.attr("class")?.lowercase() ?: ""
                    
                    if (!dataValue.isNullOrBlank()) {
                        val extractedUrl = when {
                            serverType.contains("ok") -> {
                                if (dataValue.matches(Regex("\\d+"))) {
                                    "https://ok.ru/video/$dataValue"
                                } else {
                                    null
                                }
                            }
                            serverType.contains("mega") -> {
                                if (dataValue.contains("#")) {
                                    val parts = dataValue.split("#")
                                    if (parts.size >= 2) {
                                        "https://mega.nz/file/${parts[0]}#${parts[1]}"
                                    } else {
                                        "https://mega.nz/file/$dataValue"
                                    }
                                } else {
                                    "https://mega.nz/file/$dataValue"
                                }
                            }
                            serverType.contains("videa") -> {
                                "https://videa.hu/videok/$dataValue"
                            }
                            serverType.contains("mp4upload") -> {
                                "https://mp4upload.com/embed-$dataValue.html"
                            }
                            else -> null
                        }
                        
                        if (extractedUrl != null) {
                            if (loadExtractor(extractedUrl, "$mainUrl/", subtitleCallback, callback)) {
                                return true
                            }
                        }
                    }
                }
            }
            
            false
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
    
    private fun fixUrl(url: String): String {
        return when {
            url.startsWith("http") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "$mainUrl$url"
            url.startsWith("?") -> "$mainUrl/$url"
            else -> "$mainUrl/$url"
        }.replace("?resize=247,350", "") // Remove resize parameters
    }
}
