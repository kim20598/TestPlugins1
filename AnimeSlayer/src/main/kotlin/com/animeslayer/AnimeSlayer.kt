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
        "$mainUrl/anime-slayer-home/" to "الصفحة الرئيسية",
        "$mainUrl/anime/?status=ongoing" to "الأنمي المستمر",
        "$mainUrl/anime/?status=completed&order=rating" to "الأعلى تقييماً",
        "$mainUrl/anime/?status=completed" to "الأنمي المكتمل"
    )

    private fun Element.toSearchResult(): SearchResponse? {
        val href = this.selectFirst("a[href]")?.attr("href")?.trim() ?: return null
        val title = this.selectFirst(".tt h2, .tt, h2")?.text()?.trim() 
            ?: this.selectFirst("img")?.attr("alt")?.trim()
            ?: return null
        
        val poster = this.selectFirst("img")?.attr("src")?.let { fixUrl(it) }
        
        val typeClass = this.selectFirst(".typez")?.text()?.lowercase() 
            ?: this.selectFirst(".typez")?.attr("class")?.lowercase()
        
        val type = when {
            typeClass?.contains("movie") == true -> TvType.AnimeMovie
            typeClass?.contains("ova") == true -> TvType.OVA
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
        
        val items = doc.select("article.bs, .bsx").mapNotNull { it.toSearchResult() }
        
        return newHomePageResponse(request.name, items, items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val doc = app.get("$mainUrl/?s=$encoded").document
        
        return doc.select("article.bs, .bsx").mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        
        val title = doc.selectFirst("h1.entry-title")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: "Unknown"
        
        val cleanTitle = title.replace(" - Anime Slayer Web | موقع انمي سلاير ويب", "")
            .replace("Anime Slayer Web | موقع انمي سلاير ويب", "")
            .trim()
        
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")?.let { fixUrl(it) }
            ?: doc.selectFirst(".thumb img, .thumbook img")?.attr("src")?.let { fixUrl(it) }
        
        val plot = doc.selectFirst(".desc, .entry-content")?.text()?.trim()
            ?: doc.selectFirst("meta[name=description]")?.attr("content")?.trim()
        
        val typeText = doc.selectFirst(".typez")?.text()?.lowercase()
            ?: doc.selectFirst(".typez")?.attr("class")?.lowercase()
        
        val isMovie = when {
            typeText?.contains("movie") == true -> true
            else -> {
                val episodeCount = getEpisodeCount(doc)
                episodeCount == 1 || episodeCount == 0
            }
        }
        
        val episodes = mutableListOf<Episode>()
        
        if (!isMovie) {
            val noscriptElement = doc.selectFirst("noscript#diplayer")
            if (noscriptElement != null) {
                val noscriptHtml = noscriptElement.html()
                val noscriptDoc = org.jsoup.Jsoup.parse(noscriptHtml)
                
                val episodeElements = noscriptDoc.select("#EpList1 .CSB")
                if (episodeElements.isNotEmpty()) {
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
            
            if (episodes.isEmpty()) {
                val episodeElements = doc.select("#EpList1 .CSB")
                if (episodeElements.isNotEmpty()) {
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
                    val eplisterItems = doc.select(".eplister ul li")
                    if (eplisterItems.isNotEmpty()) {
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
        }
        
        episodes.sortBy { it.episode }
        
        val year = doc.selectFirst("span.split:contains(تم الإصدار:)")?.text()
            ?.filter { it.isDigit() }?.take(4)?.toIntOrNull()
        
        val tags = doc.select(".genxed a").map { it.text().trim() }
        
        return if (isMovie || episodes.isEmpty()) {
            newMovieLoadResponse(cleanTitle, url, TvType.AnimeMovie, url) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
            }
        } else {
            newTvSeriesLoadResponse(cleanTitle, url, TvType.Anime, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
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
            var foundAnyLink = false
            
            val noscriptElement = doc.selectFirst("noscript#diplayer")
            if (noscriptElement != null) {
                val noscriptHtml = noscriptElement.html()
                val noscriptDoc = org.jsoup.Jsoup.parse(noscriptHtml)
                
                val serverContainers = noscriptDoc.select(".divv11")
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
                                serverType.contains("uqload") -> {
                                    "https://uqload.com/embed-$dataValue.html"
                                }
                                serverType.contains("dailymotion") -> {
                                    "https://www.dailymotion.com/embed/video/$dataValue"
                                }
                                serverType.contains("drive") -> {
                                    "https://drive.google.com/file/d/$dataValue/view"
                                }
                                else -> null
                            }
                            
                            if (extractedUrl != null) {
                                if (loadExtractor(extractedUrl, "$mainUrl/", subtitleCallback, callback)) {
                                    foundAnyLink = true
                                }
                            }
                        }
                    }
                }
            }
            
            val downloadLinks = doc.select(".linkul li a")
            downloadLinks.forEach { linkElement ->
                val href = linkElement.attr("href")?.trim()
                if (!href.isNullOrBlank()) {
                    if (loadExtractor(href, "$mainUrl/", subtitleCallback, callback)) {
                        foundAnyLink = true
                    }
                }
            }
            
            foundAnyLink
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
    
    private fun getEpisodeCount(doc: org.jsoup.nodes.Document): Int {
        val noscriptElement = doc.selectFirst("noscript#diplayer")
        if (noscriptElement != null) {
            val noscriptHtml = noscriptElement.html()
            val noscriptDoc = org.jsoup.Jsoup.parse(noscriptHtml)
            val episodeElements = noscriptDoc.select("#EpList1 .CSB")
            if (episodeElements.isNotEmpty()) {
                return episodeElements.size
            }
        }
        
        val episodeElements = doc.select("#EpList1 .CSB")
        if (episodeElements.isNotEmpty()) {
            return episodeElements.size
        }
        
        val eplisterItems = doc.select(".eplister ul li")
        if (eplisterItems.isNotEmpty()) {
            return eplisterItems.size
        }
        
        return 0
    }
    
    private fun fixUrl(url: String): String {
        return when {
            url.startsWith("http") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "$mainUrl$url"
            else -> "$mainUrl/$url"
        }.replace("?resize=247,350", "")
    }
}
