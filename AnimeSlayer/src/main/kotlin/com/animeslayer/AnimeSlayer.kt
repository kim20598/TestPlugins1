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

    // Parse anime cards
    private fun Element.toSearchResult(): SearchResponse? {
        val href = this.selectFirst("a")?.attr("href") ?: return null
        val title = this.selectFirst(".tt h2, .tt, h2")?.text()?.trim() 
            ?: this.selectFirst("img")?.attr("alt")?.trim()
            ?: return null
            
        val poster = this.selectFirst("img")?.attr("src")?.let { fixUrl(it) }
        
        // Get type from the div
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
        
        // Check if it's a movie
        val typeClass = doc.selectFirst(".typez")?.text()?.lowercase() 
            ?: doc.selectFirst(".typez")?.attr("class")?.lowercase()
        
        val isMovie = when {
            typeClass?.contains("movie") == true -> true
            else -> {
                val episodeCount = getEpisodeCount(doc)
                episodeCount == 1 || episodeCount == 0
            }
        }
        
        // Extract episodes
        val episodes = extractEpisodes(doc, url)
        
        // Sort episodes by number
        episodes.sortBy { it.episode }
        
        // Get year
        val year = doc.selectFirst("span.split:contains(تم الإصدار:)")?.text()
            ?.filter { it.isDigit() }?.take(4)?.toIntOrNull()
        
        // Get tags
        val tags = doc.select(".genxed a").map { it.text().trim() }
        
        // Return appropriate response
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
            
            // Get ALL server containers from BOTH noscript and regular page
            val allServers = mutableListOf<Pair<String, String>>() // (serverType, dataValue)
            
            // 1. Extract from noscript element
            val noscriptElement = doc.selectFirst("noscript#diplayer")
            if (noscriptElement != null) {
                val noscriptHtml = noscriptElement.html()
                val noscriptDoc = org.jsoup.Jsoup.parse(noscriptHtml)
                allServers.addAll(extractServersFromDocument(noscriptDoc, episode))
            }
            
            // 2. Extract from regular page
            allServers.addAll(extractServersFromDocument(doc, episode))
            
            // 3. Extract from download links section
            val downloadLinks = doc.select(".linkul li a, .download-links a, a[href*='drive.google.com'], a[href*='mega.nz']")
            downloadLinks.forEach { linkElement ->
                val href = linkElement.attr("href")?.trim()
                if (!href.isNullOrBlank()) {
                    val serverType = when {
                        href.contains("drive.google.com") -> "drive"
                        href.contains("mega.nz") -> "mega"
                        else -> "direct"
                    }
                    allServers.add((serverType to href))
                }
            }
            
            // Debug: Print all found servers
            println("Found ${allServers.size} servers: $allServers")
            
            // Process ALL extracted servers
            allServers.forEach { (serverType, dataValue) ->
                val extractedUrl = generateUrlFromServerData(serverType, dataValue)
                if (extractedUrl != null) {
                    println("Trying server: $serverType with URL: $extractedUrl")
                    if (loadExtractor(extractedUrl, "$mainUrl/", subtitleCallback, callback)) {
                        foundAnyLink = true
                        println("✓ Successfully loaded extractor for: $serverType")
                    } else {
                        println("✗ Failed to load extractor for: $serverType")
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
    
    // Helper function to extract servers from any document (noscript or regular)
    private fun extractServersFromDocument(doc: org.jsoup.nodes.Document, episode: Int): List<Pair<String, String>> {
        val servers = mutableListOf<Pair<String, String>>()
        
        // Find all server containers
        val serverContainers = doc.select(".divv11, .server-container, .divv")
        if (serverContainers.isNotEmpty()) {
            // Get the correct container for the episode
            val containerIndex = episode - 1
            if (containerIndex < serverContainers.size) {
                val container = serverContainers[containerIndex]
                val serverElements = container.select(".ul-server-position1 li, .server-list li, .server-item, li[data], .server-btn")
                
                serverElements.forEach { server ->
                    val dataValue = server.attr("data")?.trim()
                    var serverType = server.attr("type")?.lowercase() 
                        ?: server.attr("class")?.lowercase()
                        ?: server.text().lowercase()
                    
                    // Clean up server type
                    serverType = serverType.replace("videoselect", "")
                        .replace("server", "")
                        .replace("btn", "")
                        .replace("-", "")
                        .replace("_", "")
                        .trim()
                    
                    if (!dataValue.isNullOrBlank() && serverType.isNotBlank()) {
                        servers.add(serverType to dataValue)
                    }
                }
            }
        }
        
        return servers
    }
    
    // Smart function to generate URLs from server data
    private fun generateUrlFromServerData(serverType: String, dataValue: String): String? {
        return when {
            // Direct URLs already
            dataValue.startsWith("http") -> dataValue
            
            // MEGA - IMPORTANT: Check if it's a file ID or full URL
            serverType.contains("mega") -> {
                if (dataValue.startsWith("http")) {
                    dataValue
                } else if (dataValue.contains("#")) {
                    // Format: fileID#decryptionKey
                    val parts = dataValue.split("#")
                    if (parts.size >= 2) {
                        "https://mega.nz/file/${parts[0]}#${parts[1]}"
                    } else {
                        "https://mega.nz/file/$dataValue"
                    }
                } else {
                    // Could be just the file ID, try different formats
                    val megaUrls = listOf(
                        "https://mega.nz/file/$dataValue",
                        "https://mega.nz/#!$dataValue",
                        "https://mega.nz/#!$dataValue!decryptionKey",
                        "https://mega.nz/folder/$dataValue"
                    )
                    
                    // Return the first one, CloudStream extractor will handle validation
                    megaUrls.firstOrNull()
                }
            }
            
            // Google Drive - IMPORTANT: Check if it's already a full URL or just ID
            serverType.contains("drive") || serverType.contains("google") -> {
                if (dataValue.startsWith("http")) {
                    dataValue
                } else if (dataValue.contains("/d/")) {
                    // Contains Google Drive URL pattern
                    "https://drive.google.com$dataValue"
                } else {
                    // Assume it's a file ID
                    val driveUrls = listOf(
                        "https://drive.google.com/file/d/$dataValue/view",
                        "https://drive.google.com/file/d/$dataValue/preview",
                        "https://drive.google.com/uc?id=$dataValue",
                        "https://drive.google.com/open?id=$dataValue"
                    )
                    
                    // Try different Google Drive URL formats
                    driveUrls.firstOrNull()
                }
            }
            
            // YOURUPLOAD
            serverType.contains("yourupload") -> 
                "https://www.yourupload.com/embed/$dataValue"
            
            // OK.ru
            serverType.contains("ok") -> 
                if (dataValue.matches(Regex("\\d+"))) "https://ok.ru/video/$dataValue" else null
            
            // VIDEA
            serverType.contains("videa") -> {
                if (dataValue.startsWith("http")) {
                    dataValue
                } else {
                    // Try multiple Videa URL formats
                    val videaUrls = listOf(
                        "https://videa.hu/player?v=$dataValue",
                        "https://videa.hu/videok/$dataValue",
                        "https://videa.hu/v/$dataValue"
                    )
                    videaUrls.firstOrNull()
                }
            }
            
            // MAIL.RU
            serverType.contains("mailru") -> 
                "https://my.mail.ru/video/embed/$dataValue"
            
            // MP4Upload
            serverType.contains("mp4upload") -> 
                "https://mp4upload.com/embed-$dataValue.html"
            
            // UQLoad
            serverType.contains("uqload") -> 
                "https://uqload.com/embed-$dataValue.html"
            
            // Dailymotion
            serverType.contains("dailymotion") || serverType.contains("dm") -> 
                "https://www.dailymotion.com/embed/video/$dataValue"
            
            // 4shared
            serverType.contains("4shared") -> 
                "https://www.4shared.com/video/$dataValue"
            
            // ASNWISH
            serverType.contains("asnwish") -> 
                "https://asnwish.com/e/$dataValue"
            
            // Streamtape
            serverType.contains("streamtape") -> 
                "https://streamtape.com/e/$dataValue"
            
            // StreamSB
            serverType.contains("streamsb") || serverType.contains("sbplay") -> 
                "https://streamsb.com/e/$dataValue.html"
            
            // MixDrop
            serverType.contains("mixdrop") -> 
                "https://mixdrop.co/e/$dataValue"
            
            // Try to detect other common patterns
            dataValue.length > 10 && dataValue.contains("/") -> 
                if (dataValue.startsWith("/")) "$mainUrl$dataValue" else dataValue
            
            dataValue.contains(".") && !dataValue.contains(" ") -> 
                "https://$dataValue"
            
            // Default case - try as direct URL
            else -> try {
                if (dataValue.startsWith("/")) "$mainUrl$dataValue" else dataValue
            } catch (e: Exception) {
                null
            }
        }
    }
    
    // Helper function to extract episodes
    private fun extractEpisodes(doc: org.jsoup.nodes.Document, baseUrl: String): MutableList<Episode> {
        val episodes = mutableListOf<Episode>()
        
        // First check in the noscript element
        val noscriptElement = doc.selectFirst("noscript#diplayer")
        if (noscriptElement != null) {
            val noscriptHtml = noscriptElement.html()
            val noscriptDoc = org.jsoup.Jsoup.parse(noscriptHtml)
            
            val episodeElements = noscriptDoc.select("#EpList1 .CSB, .CSB, [data-episode]")
            if (episodeElements.isNotEmpty()) {
                episodeElements.forEachIndexed { index, element ->
                    val episodeNum = element.attr("data-episode").toIntOrNull() ?: (index + 1)
                    val episodeName = element.text().trim()
                    
                    episodes.add(
                        newEpisode("$baseUrl?ep=$episodeNum") {
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
            val episodeElements = doc.select("#EpList1 .CSB, .CSB, [data-episode], .eplister li")
            if (episodeElements.isNotEmpty()) {
                episodeElements.forEachIndexed { index, element ->
                    val episodeNum = element.attr("data-episode").toIntOrNull() 
                        ?: element.selectFirst(".eph-num, .epnum")?.text()?.filter { it.isDigit() }?.toIntOrNull()
                        ?: (index + 1)
                    
                    val episodeName = element.text().trim()
                        .replace(Regex("\\d+"), "")
                        .trim()
                    
                    if (episodeName.isNotBlank()) {
                        episodes.add(
                            newEpisode("$baseUrl?ep=$episodeNum") {
                                this.name = episodeName
                                this.episode = episodeNum
                                this.season = 1
                            }
                        )
                    }
                }
            }
        }
        
        return episodes
    }
    
    // Helper function to get episode count
    private fun getEpisodeCount(doc: org.jsoup.nodes.Document): Int {
        // Try multiple methods to get episode count
        val counts = mutableListOf<Int>()
        
        // 1. Count from episode list
        val episodeElements = doc.select("#EpList1 .CSB, .CSB, [data-episode]")
        if (episodeElements.isNotEmpty()) {
            counts.add(episodeElements.size)
        }
        
        // 2. Count from eplister
        val eplisterItems = doc.select(".eplister ul li, .eplister li")
        if (eplisterItems.isNotEmpty()) {
            counts.add(eplisterItems.size)
        }
        
        // 3. Parse from text
        val episodeInfo = doc.select("span:contains(الحلقات:), b:contains(الحلقات:), span:contains(عدد الحلقات:)").firstOrNull()
        val epCountText = episodeInfo?.text() ?: ""
        val epCount = Regex("""\d+""").find(epCountText)?.value?.toIntOrNull()
        if (epCount != null) {
            counts.add(epCount)
        }
        
        // Return the maximum count found, or 0 if none
        return counts.maxOrNull() ?: 0
    }
    
    private fun fixUrl(url: String): String {
        if (url.isBlank()) return ""
        
        return when {
            url.startsWith("http") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "$mainUrl$url"
            else -> "$mainUrl/$url"
        }.replace(Regex("\\?resize=\\d+,\\d+"), "")
    }
}
