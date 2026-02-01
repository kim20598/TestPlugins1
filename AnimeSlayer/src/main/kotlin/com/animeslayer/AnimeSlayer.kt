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

    // Parse anime cards from multiple possible layouts
    private fun Element.toSearchResult(): SearchResponse? {
        // Try different selectors for the card content
        val card = this.selectFirst(".bsx") ?: this
        
        val href = card.selectFirst("a[href]")?.attr("href")?.trim() ?: return null
        val title = card.selectFirst(".tt h2, .tt, h2")?.text()?.trim() 
            ?: card.selectFirst("img")?.attr("alt")?.trim()
            ?: card.selectFirst("a[href]")?.attr("title")?.trim()
            ?: return null
        
        val poster = card.selectFirst("img")?.attr("src")?.let { fixUrl(it) }
        
        // Get type from the div - IMPORTANT: Check for Movie type
        val typeClass = card.selectFirst(".typez")?.text()?.lowercase() 
            ?: card.selectFirst(".typez")?.attr("class")?.lowercase()
        
        val type = when {
            typeClass?.contains("movie") == true -> TvType.AnimeMovie
            typeClass?.contains("ova") == true -> TvType.OVA
            else -> TvType.Anime  // TV, special, etc.
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
        
        // Try multiple selectors for anime cards
        val items = doc.select("article.bs, article.bs.dd1, .bsx, .listupd article, .listupd .bs").mapNotNull { 
            it.toSearchResult() 
        }
        
        return newHomePageResponse(request.name, items, items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val doc = app.get("$mainUrl/?s=$encoded").document
        
        return doc.select("article.bs, article.bs.dd1, .bsx, .listupd article, article").mapNotNull { it.toSearchResult() }
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
            ?: doc.selectFirst(".thumb img, .thumbook img, .bigcontent img")?.attr("src")?.let { fixUrl(it) }
        
        // Plot
        val plot = doc.selectFirst(".desc, .entry-content, .bigcontent .desc")?.text()?.trim()
            ?: doc.selectFirst("meta[name=description]")?.attr("content")?.trim()
        
        // BETTER DETECTION: Check multiple indicators for movie vs series
        val typeText = doc.selectFirst(".typez")?.text()?.lowercase()
            ?: doc.selectFirst(".typez")?.attr("class")?.lowercase()
        
        val isMovie = when {
            // 1. Check type badge on the page
            typeText?.contains("movie") == true -> true
            
            // 2. Check in the info section for "فيلم" (movie in Arabic)
            doc.selectFirst("span:contains(النوع:), b:contains(النوع:)")?.let { element ->
                val nextElement = element.nextElementSibling()
                val text = (nextElement?.text() ?: element.text()).lowercase()
                text.contains("فيلم") || text.contains("movie")
            } == true -> true
            
            // 3. Check episode list - if no episodes or only 1 episode, might be a movie
            else -> {
                val episodeCount = getEpisodeCount(doc)
                episodeCount == 1 || episodeCount == 0
            }
        }
        
        // Extract episodes from the noscript element (more reliable)
        val episodes = mutableListOf<Episode>()
        
        // Only extract episodes if it's NOT a movie
        if (!isMovie) {
            // First check in the noscript element
            val noscriptElement = doc.selectFirst("noscript#diplayer")
            if (noscriptElement != null) {
                val noscriptHtml = noscriptElement.html()
                val noscriptDoc = org.jsoup.Jsoup.parse(noscriptHtml)
                
                val episodeElements = noscriptDoc.select("#EpList1 .CSB, .CSB")
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
            
            // If no episodes found in noscript, try regular way
            if (episodes.isEmpty()) {
                val episodeElements = doc.select("#EpList1 .CSB, .CSB")
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
                    // Check episode count from the eplister section
                    val eplisterItems = doc.select(".eplister ul li, .eplister li")
                    if (eplisterItems.isNotEmpty()) {
                        eplisterItems.forEach { item ->
                            val episodeNumText = item.selectFirst(".eph-num, .epnum")?.text()?.trim()
                            val episodeNum = episodeNumText?.filter { it.isDigit() }?.toIntOrNull()
                            val episodeTitle = item.selectFirst(".eph-title, .eptitle")?.text()?.trim()
                            
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
        
        // Sort episodes by number
        episodes.sortBy { it.episode }
        
        // Get year from release info
        val yearText = doc.selectFirst("span.split:contains(تم الإصدار:), span:contains(تم الإصدار:), b:contains(تم الإصدار:)")?.text()
        val year = yearText?.filter { it.isDigit() }?.take(4)?.toIntOrNull()
        
        // Get tags/genres
        val tags = doc.select(".genxed a, .genres a, .genre a").map { it.text().trim() }
        
        // Return appropriate response
        return if (isMovie) {
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
            
            // 1. First try to get links from the noscript element (the actual video links)
            val noscriptElement = doc.selectFirst("noscript#diplayer")
            if (noscriptElement != null) {
                // Parse the HTML inside the noscript tag
                val noscriptHtml = noscriptElement.html()
                val noscriptDoc = org.jsoup.Jsoup.parse(noscriptHtml)
                
                // Get all divv11 containers (each contains servers for one episode)
                val serverContainers = noscriptDoc.select(".divv11, .divv")
                if (serverContainers.isNotEmpty() && serverContainers.size >= episode) {
                    val container = serverContainers[episode - 1]
                    val servers = container.select(".ul-server-position1 li, .server-item, .server-list li")
                    
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
                                serverType.contains("drive") -> {
                                    // Google Drive links
                                    "https://drive.google.com/file/d/$dataValue/view"
                                }
                                else -> null
                            }
                            
                            if (extractedUrl != null) {
                                // Try to load extractor
                                if (loadExtractor(extractedUrl, "$mainUrl/", subtitleCallback, callback)) {
                                    foundAnyLink = true
                                }
                            }
                        }
                    }
                }
            }
            
            // 2. Also check the direct download section
            val downloadLinks = doc.select(".linkul li a, .download-links a, a[href*='drive.google.com'], a[href*='mega.nz']")
            downloadLinks.forEach { linkElement ->
                val href = linkElement.attr("href")?.trim()
                if (!href.isNullOrBlank()) {
                    // Try to load extractor
                    if (loadExtractor(href, "$mainUrl/", subtitleCallback, callback)) {
                        foundAnyLink = true
                    }
                }
            }
            
            // 3. If still no links found, try the regular way (fallback)
            if (!foundAnyLink) {
                val serverContainers = doc.select(".divv11, .divv")
                if (serverContainers.isNotEmpty() && serverContainers.size >= episode) {
                    val container = serverContainers[episode - 1]
                    val servers = container.select(".ul-server-position1 li, .server-list li")
                    
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
                                serverType.contains("drive") -> {
                                    "https://drive.google.com/file/d/$dataValue/view"
                                }
                                else -> null
                            }
                            
                            if (extractedUrl != null) {
                                // Try to load extractor
                                if (loadExtractor(extractedUrl, "$mainUrl/", subtitleCallback, callback)) {
                                    foundAnyLink = true
                                }
                            }
                        }
                    }
                }
            }
            
            // Return true if we found ANY links at all
            foundAnyLink
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
    
    // Helper function to get episode count from various sources
    private fun getEpisodeCount(doc: org.jsoup.nodes.Document): Int {
        // 1. Check noscript element first
        val noscriptElement = doc.selectFirst("noscript#diplayer")
        if (noscriptElement != null) {
            val noscriptHtml = noscriptElement.html()
            val noscriptDoc = org.jsoup.Jsoup.parse(noscriptHtml)
            val episodeElements = noscriptDoc.select("#EpList1 .CSB, .CSB")
            if (episodeElements.isNotEmpty()) {
                return episodeElements.size
            }
        }
        
        // 2. Check regular page
        val episodeElements = doc.select("#EpList1 .CSB, .CSB")
        if (episodeElements.isNotEmpty()) {
            return episodeElements.size
        }
        
        // 3. Check eplister section
        val eplisterItems = doc.select(".eplister ul li, .eplister li")
        if (eplisterItems.isNotEmpty()) {
            return eplisterItems.size
        }
        
        // 4. Check info section for episode count
        val episodeInfo = doc.select("span:contains(الحلقات:), b:contains(الحلقات:), span:contains(عدد الحلقات:)").firstOrNull()
        val epCountText = episodeInfo?.text() ?: ""
        val epCount = Regex("""\d+""").find(epCountText)?.value?.toIntOrNull()
        
        return epCount ?: 0
    }
    
    private fun fixUrl(url: String): String {
        if (url.isBlank()) return ""
        
        return when {
            url.startsWith("http") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "$mainUrl$url"
            else -> "$mainUrl/$url"
        }.replace("?resize=\\d+,\\d+".toRegex(), "") // Remove resize parameters
    }
}
