package com.kooralite

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLEncoder

class KooraLite : MainAPI() {
    override var mainUrl = "https://www.kooralite.live"
    override var name = "KooraLite"
    override var lang = "ar"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie)
    
    private fun Element.toMatchSearchResponse(): SearchResponse? {
        val link = selectFirst("a")?.attr("href") ?: return null
        val href = fixUrl(link)
        
        // Get team names
        val team1 = selectFirst(".MT_Team.TM1 .TM_Name")?.text()?.trim() ?: ""
        val team2 = selectFirst(".MT_Team.TM2 .TM_Name")?.text()?.trim() ?: ""
        
        if (team1.isBlank() || team2.isBlank()) return null
        
        val title = "$team1 vs $team2"
        
        // Get match status
        val matchDiv = this
        val statusClass = matchDiv.classNames().firstOrNull { it in listOf("live", "finished", "comming-soon") } ?: ""
        val statusText = when (statusClass) {
            "live" -> "🔴 مباشر"
            "finished" -> "✅ انتهت"
            else -> "⏳ قادم"
        }
        
        // Get match time
        val time = selectFirst(".MT_Time")?.text()?.trim() ?: ""
        
        // Get tournament
        val tournament = selectFirst(".MT_Info li:last-child span")?.text()?.trim() ?: ""
        
        // Get team logos
        val team1Logo = selectFirst(".TM_Team.TM1 .TM_Logo img")?.attr("src")?.let { 
            if (it.startsWith("http")) it else fixUrl(it) 
        }
        
        val team2Logo = selectFirst(".TM_Team.TM2 .TM_Logo img")?.attr("src")?.let { 
            if (it.startsWith("http")) it else fixUrl(it) 
        }
        
        // Choose poster
        val poster = team1Logo ?: team2Logo
        
        // Create title
        val enhancedTitle = if (time.isNotBlank()) {
            "$statusText $title ($time)"
        } else {
            "$statusText $title"
        }
        
        // Store match data
        val matchData = "$title|$time|$tournament|$statusClass|$poster|$team1|$team2|$team1Logo|$team2Logo"
        val dataUrl = "$href|$matchData"
        
        return newMovieSearchResponse(enhancedTitle, dataUrl, TvType.Movie) {
            this.posterUrl = poster
        }
    }
    
    private fun Element.toArticleSearchResponse(): SearchResponse? {
        // For articles
        val link = selectFirst("a")?.attr("href") ?: return null
        val href = fixUrl(link)
        
        val title = selectFirst(".gr-title, h3")?.text()?.trim() ?: return null
        val poster = selectFirst("img")?.attr("data-src")?.let {
            if (it.startsWith("http")) it else fixUrl(it)
        } ?: selectFirst("img")?.attr("src")?.let {
            if (it.startsWith("http")) it else fixUrl(it)
        }
        
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = poster
        }
    }
    
    override val mainPage = mainPageOf(
        "$mainUrl/" to "مباريات اليوم",
        "$mainUrl/matches-today/" to "المباريات الحية",
        "$mainUrl/matches-yesterday/" to "مباريات الأمس",
        "$mainUrl/matches-tomorrow/" to "مباريات الغد",
        "$mainUrl/category/sports-news/" to "أخبار رياضية",
        "$mainUrl/category/champions-league/" to "دوري أبطال أوروبا",
        "$mainUrl/category/premier-league/" to "الدوري الإنجليزي",
        "$mainUrl/category/la-liga/" to "الدوري الإسباني",
        "$mainUrl/category/serie-a/" to "الدوري الإيطالي",
        "$mainUrl/category/bundesliga/" to "الدوري الألماني",
        "$mainUrl/category/saudi-league/" to "الدوري السعودي",
        "$mainUrl/category/arab-cup/" to "كأس العرب"
    )
    
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page > 1) "${request.data}page/$page/" else request.data
        val document = app.get(url).document
        
        val items = mutableListOf<SearchResponse>()
        
        // Get matches
        if (request.data.contains("matches-") || request.data == "$mainUrl/") {
            document.select(".AY_Match").forEach { match ->
                match.toMatchSearchResponse()?.let { items.add(it) }
            }
        }
        
        // Get articles
        if (items.isEmpty()) {
            document.select(".gr-item, article, .post").forEach { article ->
                article.toArticleSearchResponse()?.let { items.add(it) }
            }
        }
        
        return newHomePageResponse(request.name, items, hasNext = true)
    }
    
    override suspend fun search(query: String): List<SearchResponse> {
        if (query.length < 2) return emptyList()
        
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val searchUrl = "$mainUrl/?s=$encodedQuery"
        
        return try {
            val document = app.get(searchUrl).document
            val results = mutableListOf<SearchResponse>()
            
            document.select(".AY_Match").forEach { match ->
                match.toMatchSearchResponse()?.let { results.add(it) }
            }
            
            document.select(".gr-item, .search-result, article").forEach { article ->
                article.toArticleSearchResponse()?.let { results.add(it) }
            }
            
            results
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    override suspend fun load(url: String): LoadResponse {
        // Parse stored match data
        val parts = url.split("|")
        val actualUrl = parts[0]
        val title = parts.getOrNull(1) ?: "مباراة كرة قدم"
        val time = parts.getOrNull(2) ?: ""
        val tournament = parts.getOrNull(3) ?: ""
        val status = parts.getOrNull(4) ?: ""
        val poster = parts.getOrNull(5)
        val team1 = parts.getOrNull(6) ?: ""
        val team2 = parts.getOrNull(7) ?: ""
        val team1Logo = parts.getOrNull(8)
        val team2Logo = parts.getOrNull(9)
        
        val document = app.get(actualUrl).document
        
        // Build clean description
        val description = buildString {
            if (team1.isNotBlank() && team2.isNotBlank()) {
                append("⚽ $team1 vs $team2\n")
            }
            
            if (time.isNotBlank()) {
                append("🕒 الوقت: $time\n")
            }
            
            if (tournament.isNotBlank()) {
                append("🏆 البطولة: $tournament\n")
            }
            
            // Add status
            when (status) {
                "live" -> append("🔴 الحالة: البث مباشر الآن\n")
                "finished" -> append("✅ الحالة: انتهت المباراة\n")
                else -> append("⏳ الحالة: قادمة\n")
            }
            
            // Extract match info from table
            val matchTable = document.select("table.table-bordered")
            if (matchTable.isNotEmpty()) {
                append("\n📋 معلومات المباراة:\n")
                
                matchTable.select("tr").forEach { row ->
                    val header = row.select("th").text().trim()
                    val value = row.select("td").text().trim()
                    
                    if (header.isNotBlank() && value.isNotBlank()) {
                        append("• $header: $value\n")
                    }
                }
            }
            
            // Add server information if available
            val servers = document.select(".video-serv a")
            if (servers.isNotEmpty()) {
                append("\n🔗 السيرفرات المتاحة: ${servers.size}\n")
            }
        }
        
        // Look for stream links
        val streamLinks = mutableSetOf<String>()
        
        // Look for iframes
        document.select("iframe[src]").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.isNotBlank() && src.contains("stream")) {
                streamLinks.add(src)
            }
        }
        
        // Check if URL is already a stream link
        if (actualUrl.contains("stream-in.live") || actualUrl.contains("stream")) {
            streamLinks.add(actualUrl)
        }
        
        // Look for video elements
        document.select("video source[src]").forEach { source ->
            val src = source.attr("src")
            if (src.isNotBlank()) {
                streamLinks.add(src)
            }
        }
        
        val data = if (streamLinks.isNotEmpty()) {
            streamLinks.joinToString("|||")
        } else {
            actualUrl
        }
        
        return newMovieLoadResponse(title, url, TvType.Movie, data) {
            this.posterUrl = poster
            this.plot = description.trim()
            
            // Add tags
            val tags = mutableListOf("كرة قدم", "رياضة", "مباراة")
            
            if (tournament.isNotBlank()) {
                tags.add(tournament)
            }
            
            if (status == "live") {
                tags.add("بث مباشر")
            }
            
            if (team1.isNotBlank()) {
                tags.add(team1)
            }
            if (team2.isNotBlank()) {
                tags.add(team2)
            }
            
            this.tags = tags
        }
    }
    
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var foundLinks = false
        
        // Check if data contains multiple stream links
        if (data.contains("|||")) {
            val streamLinks = data.split("|||").filter { it.isNotBlank() }
            
            streamLinks.forEach { streamUrl ->
                try {
                    loadExtractor(streamUrl, mainUrl, subtitleCallback, callback)
                    foundLinks = true
                } catch (e: Exception) {
                    // Try direct extraction
                    tryExtractDirectLink(streamUrl, callback)
                }
            }
        } else {
            // Single URL
            try {
                val doc = app.get(data).document
                
                // Look for iframes
                doc.select("iframe[src*='alkoora.live'], iframe[src*='stream-in.live']").forEach { iframe ->
                    val src = iframe.attr("src")
                    if (src.isNotBlank()) {
                        // Extract from the iframe
                        foundLinks = extractStreamFromIframe(src, data, subtitleCallback, callback) || foundLinks
                    }
                }
                
                // Look for other iframes
                doc.select("iframe[src]").forEach { iframe ->
                    val src = iframe.attr("src")
                    if (src.isNotBlank() && !src.contains("alkoora.live") && !src.contains("stream-in.live")) {
                        loadExtractor(src, data, subtitleCallback, callback)
                        foundLinks = true
                    }
                }
                
                // Look for direct video links
                doc.select("video source[src]").forEach { source ->
                    val videoUrl = source.attr("src")
                    if (videoUrl.isNotBlank()) {
                        callback.invoke(
                            ExtractorLink(
                                name,
                                "$name - بث مباشر",
                                videoUrl,
                                mainUrl,
                                Qualities.Unknown.value,
                                videoUrl.contains(".m3u8")
                            )
                        )
                        foundLinks = true
                    }
                }
                
            } catch (e: Exception) {
                // Error loading page
            }
        }
        
        // If still no links found
        if (!foundLinks && (data.contains("stream-in.live") || data.contains("/2025/"))) {
            try {
                loadExtractor(data, mainUrl, subtitleCallback, callback)
                foundLinks = true
            } catch (e: Exception) {
                // Ignore
            }
        }
        
        return foundLinks
    }
    
    // Function to extract streams from iframes
    private suspend fun extractStreamFromIframe(
        iframeSrc: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var foundLinks = false
        
        try {
            val iframeDoc = app.get(iframeSrc, referer = referer).document
            
            // Look for video elements in iframe
            iframeDoc.select("video source[src]").forEach { source ->
                val videoUrl = source.attr("src")
                if (videoUrl.isNotBlank()) {
                    callback.invoke(
                        ExtractorLink(
                            name,
                            "$name - بث مباشر",
                            videoUrl,
                            iframeSrc,
                            Qualities.Unknown.value,
                            videoUrl.contains(".m3u8")
                        )
                    )
                    foundLinks = true
                }
            }
            
            // Look for streaming scripts in iframe
            val iframeScripts = iframeDoc.select("script").html()
            
            // Check for common streaming URL patterns
            val patterns = listOf(
                Regex("""(https?://[^\s'"]*\.m3u8[^\s'"]*)"""),
                Regex("""['"](https?://[^'"]*stream[^'"]*)['"]"""),
                Regex("""['"](https?://[^'"]*\.mp4[^'"]*)['"]""")
            )
            
            patterns.forEach { pattern ->
                pattern.findAll(iframeScripts).forEach { match ->
                    val url = match.groupValues[1]
                    if (url.isNotBlank() && (url.contains("m3u8") || url.contains("mp4") || url.contains("stream"))) {
                        loadExtractor(url, iframeSrc, subtitleCallback, callback)
                        foundLinks = true
                    }
                }
            }
            
        } catch (e: Exception) {
            // If iframe extraction fails, try the iframe URL directly
            try {
                loadExtractor(iframeSrc, referer, subtitleCallback, callback)
                foundLinks = true
            } catch (e: Exception) {
                // Ignore
            }
        }
        
        return foundLinks
    }
    
    private fun tryExtractDirectLink(url: String, callback: (ExtractorLink) -> Unit) {
        try {
            // Check if it's a direct video URL
            if (url.contains(".m3u8") || url.contains(".mp4") || url.contains(".mkv")) {
                callback.invoke(
                    ExtractorLink(
                        name,
                        "$name - بث مباشر",
                        url,
                        mainUrl,
                        Qualities.Unknown.value,
                        url.contains(".m3u8")
                    )
                )
            }
        } catch (e: Exception) {
            // Ignore errors
        }
    }
    
    private fun fixUrl(url: String): String {
        return when {
            url.isBlank() -> ""
            url.startsWith("http") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "$mainUrl$url"
            else -> "$mainUrl/$url"
        }
    }
}
