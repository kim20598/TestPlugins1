package com.kooralite

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLEncoder

class KooraLite : MainAPI() {
    override var mainUrl = "https://www.kooralite.live"
    override var name = "KooraLite - كورة لايت"
    override var lang = "ar"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie)
    
    private fun Element.toMatchSearchResponse(): SearchResponse? {
        // Extract match information from .AY_Match div
        val link = selectFirst("a")?.attr("href") ?: return null
        val href = fixUrl(link)
        
        // Get team names
        val team1 = selectFirst(".MT_Team.TM1 .TM_Name")?.text()?.trim() ?: ""
        val team2 = selectFirst(".MT_Team.TM2 .TM_Name")?.text()?.trim() ?: ""
        
        if (team1.isBlank() || team2.isBlank()) return null
        
        // Create title: Team1 vs Team2
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
        
        // Get tournament/league
        val tournament = selectFirst(".MT_Info li:last-child span")?.text()?.trim() ?: ""
        
        // Get poster/logo (team logo)
        val poster = selectFirst(".TM_Logo img")?.attr("src")?.let { 
            if (it.startsWith("http")) it else fixUrl(it) 
        }
        
        // Create enhanced title
        val enhancedTitle = "$statusText $title"
        
        // Store match data
        val matchData = "$title|$time|$tournament|$statusClass|$poster|$team1|$team2"
        val dataUrl = "$href|$matchData"
        
        return newMovieSearchResponse(enhancedTitle, dataUrl, TvType.Movie) {
            this.posterUrl = poster
        }
    }
    
    private fun Element.toArticleSearchResponse(): SearchResponse? {
        // For regular articles/posts
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
        
        // First, get live matches (only on main pages with matches)
        if (request.data.contains("matches-") || request.data == "$mainUrl/") {
            document.select(".AY_Match").forEach { match ->
                match.toMatchSearchResponse()?.let { items.add(it) }
            }
        }
        
        // If no matches found, get articles/posts
        if (items.isEmpty()) {
            document.select(".gr-item, article, .post").forEach { article ->
                article.toArticleSearchResponse()?.let { items.add(it) }
            }
        }
        
        // Fallback: get any links that look like matches
        if (items.isEmpty()) {
            document.select("a").forEach { link ->
                val href = link.attr("href")
                val text = link.text().trim()
                
                if (href.contains("/match/") || href.contains("stream-in.live") || 
                    text.contains("مباراة") || text.contains("بث مباشر")) {
                    
                    val fullUrl = fixUrl(href)
                    if (text.length > 3) {
                        items.add(newMovieSearchResponse(text, fullUrl, TvType.Movie))
                    }
                }
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
            
            // Try to find matches
            document.select(".AY_Match").forEach { match ->
                match.toMatchSearchResponse()?.let { results.add(it) }
            }
            
            // Try to find articles
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
        
        val document = app.get(actualUrl).document
        
        // Build description
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
            
            // Try to find stream quality/links info
            document.select(".video-serv a").forEach { streamLink ->
                val streamName = streamLink.text().trim()
                if (streamName.isNotBlank()) {
                    append("📺 $streamName\n")
                }
            }
            
            // Add server information if available
            val servers = document.select(".video-serv a")
            if (servers.isNotEmpty()) {
                append("\n🔗 السيرفرات المتاحة: ${servers.size}\n")
            }
        }
        
        // Store the actual URL for stream extraction
        val data = actualUrl
        
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
        
        // Parse the actual stream page
        try {
            val document = app.get(data).document
            
            // Look for the main stream iframe
            document.select("iframe[src]").forEach { iframe ->
                val src = iframe.attr("src")
                if (src.isNotBlank() && (src.contains("alkoora.live") || 
                                         src.contains("stream-in.live") || 
                                         src.contains("/albaplayer/"))) {
                    
                    // This is the main stream iframe - need to extract from it
                    foundLinks = extractStreamFromIframe(src, data, subtitleCallback, callback) || foundLinks
                }
            }
            
            // Also check for direct video links
            document.select("video source[src]").forEach { source ->
                val videoUrl = source.attr("src")
                if (videoUrl.isNotBlank()) {
                    callback.invoke(
                        ExtractorLink(
                            this.name,
                            "$name - بث مباشر",
                            videoUrl,
                            data,
                            Qualities.Unknown.value,
                            videoUrl.contains(".m3u8"),
                            headers = mapOf("Referer" to data)
                        )
                    )
                    foundLinks = true
                }
            }
            
            // Look for streaming scripts
            val scriptContent = document.select("script").html()
            
            // Check for m3u8 URLs in scripts
            val m3u8Pattern = Regex("""(https?://[^\s'"]*\.m3u8[^\s'"]*)""")
            m3u8Pattern.findAll(scriptContent).forEach { match ->
                val url = match.groupValues[1]
                if (url.isNotBlank()) {
                    loadExtractor(url, data, subtitleCallback, callback)
                    foundLinks = true
                }
            }
            
            // Check for common streaming patterns
            val streamPatterns = listOf(
                Regex("""['"](https?://[^'"]*alkoora\.live[^'"]*)['"]"""),
                Regex("""['"](https?://[^'"]*stream-in\.live[^'"]*)['"]"""),
                Regex("""src\s*[:=]\s*['"](https?://[^'"]+)['"]"""),
                Regex("""file\s*[:=]\s*['"](https?://[^'"]+\.m3u8)['"]""")
            )
            
            streamPatterns.forEach { pattern ->
                pattern.findAll(scriptContent).forEach { match ->
                    val url = match.groupValues[1]
                    if (url.isNotBlank() && (url.contains("m3u8") || url.contains("stream"))) {
                        loadExtractor(url, data, subtitleCallback, callback)
                        foundLinks = true
                    }
                }
            }
            
        } catch (e: Exception) {
            // If direct page load fails, try the URL as a direct stream
            if (data.contains("stream-in.live") || data.contains("alkoora.live")) {
                try {
                    loadExtractor(data, mainUrl, subtitleCallback, callback)
                    foundLinks = true
                } catch (e: Exception) {
                    // Ignore
                }
            }
        }
        
        return foundLinks
    }
    
    private suspend fun extractStreamFromIframe(
        iframeSrc: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var foundLinks = false
        
        try {
            val iframeDoc = app.get(iframeSrc, referer = referer).document
            
            // Method 1: Look for video elements
            iframeDoc.select("video source[src]").forEach { source ->
                val videoUrl = source.attr("src")
                if (videoUrl.isNotBlank()) {
                    callback.invoke(
                        ExtractorLink(
                            this.name,
                            "$name - بث مباشر",
                            videoUrl,
                            iframeSrc,
                            Qualities.Unknown.value,
                            videoUrl.contains(".m3u8"),
                            headers = mapOf("Referer" to iframeSrc)
                        )
                    )
                    foundLinks = true
                }
            }
            
            // Method 2: Look for streaming scripts in iframe
            val iframeScripts = iframeDoc.select("script").html()
            
            // Check for common streaming URL patterns in iframe
            val patterns = listOf(
                Regex("""(https?://[^\s'"]*\.m3u8[^\s'"]*)"""),
                Regex("""['"](https?://[^'"]*stream[^'"]*)['"]"""),
                Regex("""['"](https?://[^'"]*\.mp4[^'"]*)['"]"""),
                Regex("""src\s*[:=]\s*['"](https?://[^'"]+)['"]"""),
                Regex("""file\s*[:=]\s*['"](https?://[^'"]+\.m3u8)['"]"""),
                Regex("""hls\.src\s*=\s*['"]([^'"]+)['"]"""),
                Regex("""player\.src\s*=\s*['"]([^'"]+)['"]""")
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
            
            // Method 3: Look for iframes within iframes
            iframeDoc.select("iframe[src]").forEach { nestedIframe ->
                val nestedSrc = nestedIframe.attr("src")
                if (nestedSrc.isNotBlank()) {
                    foundLinks = extractStreamFromIframe(nestedSrc, iframeSrc, subtitleCallback, callback) || foundLinks
                }
            }
            
            // Method 4: Try to load common streaming extractors
            if (!foundLinks) {
                try {
                    loadExtractor(iframeSrc, referer, subtitleCallback, callback)
                    foundLinks = true
                } catch (e: Exception) {
                    // Try to extract direct m3u8 from iframe URL pattern
                    if (iframeSrc.contains("albaplayer/")) {
                        // Try common patterns for albaplayer streams
                        val possibleStreams = listOf(
                            iframeSrc.replace("?serv=0", ".m3u8"),
                            iframeSrc.replace("?serv=0", "/playlist.m3u8"),
                            iframeSrc.replace("?serv=0", "/index.m3u8"),
                            iframeSrc.replace("albaplayer/", "stream/") + ".m3u8"
                        )
                        
                        possibleStreams.forEach { streamUrl ->
                            try {
                                // Test if the stream URL exists
                                app.get(streamUrl, timeout = 5000)
                                loadExtractor(streamUrl, iframeSrc, subtitleCallback, callback)
                                foundLinks = true
                            } catch (e: Exception) {
                                // Ignore
                            }
                        }
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