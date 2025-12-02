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
    override val supportedTypes = setOf(TvType.LiveStream, TvType.Movie)
    
    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }
    
    private fun Element.toMatchSearchResponse(): SearchResponse? {
        // Method 1: Match cards (most reliable)
        val matchCard = selectFirst(".AY_Match")
        if (matchCard != null) {
            return matchCard.toMatchCardResponse()
        }
        
        // Method 2: Article/Post items
        return toArticleResponse()
    }
    
    private fun Element.toMatchCardResponse(): SearchResponse? {
        val link = selectFirst("a")?.attr("href") ?: return null
        val href = fixUrl(link)
        
        // Extract team names from different possible selectors
        val team1 = selectFirst(".MT_Team.TM1 .TM_Name, .team1 .name, .home-team .name")?.text()?.trim()
            ?: Regex("""(.*?)\s*vs\s""").find(ownText())?.groupValues?.get(1)?.trim()
            ?: return null
            
        val team2 = selectFirst(".MT_Team.TM2 .TM_Name, .team2 .name, .away-team .name")?.text()?.trim()
            ?: Regex("""vs\s*(.*)""").find(ownText())?.groupValues?.get(1)?.trim()
            ?: return null
        
        // Create title
        val title = "$team1 vs $team2"
        
        // Get match status
        val statusClass = classNames().firstOrNull { 
            it.contains("live", true) || it.contains("finished", true) || it.contains("coming", true) 
        } ?: ""
        
        val statusText = when {
            statusClass.contains("live", true) -> "🔴 مباشر"
            statusClass.contains("finished", true) -> "✅ انتهت"
            else -> "⏳ قادمة"
        }
        
        // Get match time
        val time = selectFirst(".MT_Time, .match-time, .time")?.text()?.trim() ?: ""
        
        // Get tournament/league
        val tournament = selectFirst(".MT_Info li:last-child span, .tournament, .league")?.text()?.trim() ?: ""
        
        // Get team logos (priority: data-src -> src)
        val team1Logo = selectFirst(".TM1 img, .team1 img, .home-team img")?.let { img ->
            img.attr("data-src").ifBlank { img.attr("src") }
        }?.takeIf { it.isNotBlank() }?.let { fixUrl(it) }
        
        val team2Logo = selectFirst(".TM2 img, .team2 img, .away-team img")?.let { img ->
            img.attr("data-src").ifBlank { img.attr("src") }
        }?.takeIf { it.isNotBlank() }?.let { fixUrl(it) }
        
        // Choose poster (team1 logo, team2 logo, or default)
        val poster = team1Logo ?: team2Logo
        
        // Enhanced title with status and time
        val enhancedTitle = buildString {
            append(statusText)
            append(" ")
            append(title)
            if (time.isNotBlank()) append(" ($time)")
        }
        
        // Store all match data
        val matchData = mapOf(
            "title" to title,
            "time" to time,
            "tournament" to tournament,
            "status" to statusClass,
            "poster" to (poster ?: ""),
            "team1" to team1,
            "team2" to team2,
            "team1Logo" to (team1Logo ?: ""),
            "team2Logo" to (team2Logo ?: "")
        ).toJson()
        
        val dataUrl = "$href|$matchData"
        
        return newMovieSearchResponse(enhancedTitle, dataUrl, TvType.LiveStream) {
            this.posterUrl = poster
        }
    }
    
    private fun Element.toArticleResponse(): SearchResponse? {
        val link = selectFirst("a")?.attr("href") ?: return null
        val href = fixUrl(link)
        
        val title = selectFirst(".gr-title, h3, .title, .entry-title")?.text()?.trim() 
            ?: attr("title").trim()
            ?: return null
        
        val poster = selectFirst("img")?.let { img ->
            img.attr("data-src").ifBlank { img.attr("src") }
        }?.takeIf { it.isNotBlank() }?.let { fixUrl(it) }
        
        // Check if it's a match or regular article
        val isMatch = title.contains("vs", true) || title.contains("مباراة", true) || 
                      href.contains("/match/", true)
        
        return newMovieSearchResponse(
            title, 
            href, 
            if (isMatch) TvType.LiveStream else TvType.Movie
        ) {
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
        "$mainUrl/category/arab-cup/" to "كأس العرب",
        "$mainUrl/category/world-cup/" to "كأس العالم"
    )
    
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page > 1) "${request.data}page/$page/" else request.data
        val document = app.get(url, headers = getHeaders()).document
        
        val items = mutableListOf<SearchResponse>()
        
        // Look for matches first (highest priority)
        document.select(".AY_Match, .match-card, [class*='match']").forEach { element ->
            element.toMatchSearchResponse()?.let { items.add(it) }
        }
        
        // Look for articles/posts
        if (items.isEmpty()) {
            document.select(".gr-item, article, .post, .news-item").forEach { element ->
                element.toArticleResponse()?.let { items.add(it) }
            }
        }
        
        // Fallback: any links that might be matches
        if (items.isEmpty()) {
            document.select("a[href*='match'], a[href*='stream'], a[href*='live']").forEach { link ->
                val href = link.attr("href")
                val text = link.text().trim()
                
                if (href.isNotBlank() && text.length > 3) {
                    val fullUrl = fixUrl(href)
                    items.add(newMovieSearchResponse(text, fullUrl, TvType.LiveStream))
                }
            }
        }
        
        return newHomePageResponse(
            request.name, 
            items, 
            hasNext = items.isNotEmpty() && document.select("a.next, .pagination a").isNotEmpty()
        )
    }
    
    override suspend fun search(query: String): List<SearchResponse> {
        if (query.length < 2) return emptyList()
        
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val searchUrl = "$mainUrl/?s=$encodedQuery"
        
        return try {
            val document = app.get(searchUrl, headers = getHeaders()).document
            val results = mutableListOf<SearchResponse>()
            
            // Look for matches
            document.select(".AY_Match, .match-card").forEach { element ->
                element.toMatchSearchResponse()?.let { results.add(it) }
            }
            
            // Look for articles
            document.select(".gr-item, article, .search-result").forEach { element ->
                element.toArticleResponse()?.let { results.add(it) }
            }
            
            // Look for any relevant links
            if (results.isEmpty()) {
                document.select("a").forEach { link ->
                    val text = link.text().trim()
                    val href = link.attr("href")
                    
                    if (text.contains(query, true) && href.isNotBlank()) {
                        val fullUrl = fixUrl(href)
                        results.add(newMovieSearchResponse(text, fullUrl, TvType.LiveStream))
                    }
                }
            }
            
            results.distinctBy { it.url }
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    override suspend fun load(url: String): LoadResponse {
        // Parse the URL and stored match data
        val parts = url.split("|", limit = 2)
        val actualUrl = parts[0]
        
        // If we have stored match data, use it
        val matchData = if (parts.size > 1) {
            try {
                parseJson<Map<String, String>>(parts[1])
            } catch (e: Exception) {
                null
            }
        } else null
        
        val document = app.get(actualUrl, headers = getHeaders()).document
        
        // Extract title from page or stored data
        val title = matchData?.get("title") 
            ?: document.selectFirst("h1.entry-title, h1.title, h1")?.text()?.trim()
            ?: "مباراة كرة قدم"
        
        val team1 = matchData?.get("team1") ?: ""
        val team2 = matchData?.get("team2") ?: ""
        val time = matchData?.get("time") ?: ""
        val tournament = matchData?.get("tournament") ?: ""
        val status = matchData?.get("status") ?: ""
        
        // Get poster from stored data or page
        val poster = matchData?.get("poster")?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("meta[property='og:image']")?.attr("content")?.let { fixUrl(it) }
            ?: document.selectFirst(".poster img, .thumbnail img, img[src*='logo']")?.attr("src")?.let { fixUrl(it) }
        
        // Build detailed description
        val description = buildString {
            // Basic match info
            if (team1.isNotBlank() && team2.isNotBlank()) {
                append("⚽ **$team1 vs $team2**\n\n")
            }
            
            // Match status with emoji
            when {
                status.contains("live", true) -> append("🔴 **البث مباشر الآن**\n")
                status.contains("finished", true) -> append("✅ **انتهت المباراة**\n")
                else -> append("⏳ **المباراة قادمة**\n")
            }
            
            // Time and tournament
            if (time.isNotBlank()) {
                append("🕒 **الوقت:** $time\n")
            }
            
            if (tournament.isNotBlank()) {
                append("🏆 **البطولة:** $tournament\n")
            }
            
            // Extract match details from table if available
            val matchTable = document.select("table.table-bordered, table.match-info, .match-details")
            if (matchTable.isNotEmpty()) {
                append("\n📋 **معلومات المباراة:**\n")
                
                matchTable.select("tr").forEach { row ->
                    val header = row.select("th, .info-label").text().trim()
                    val value = row.select("td, .info-value").text().trim()
                    
                    if (header.isNotBlank() && value.isNotBlank()) {
                        val emoji = when {
                            header.contains("بطولة", true) -> "🏆"
                            header.contains("قناة", true) -> "📺"
                            header.contains("تاريخ", true) -> "📅"
                            header.contains("توقيت", true) -> "⏰"
                            header.contains("معلق", true) -> "🎙️"
                            header.contains("نتيجة", true) -> "📊"
                            header.contains("ملعب", true) -> "🏟️"
                            else -> "•"
                        }
                        append("$emoji **$header:** $value\n")
                    }
                }
            }
            
            // Extract available streams/qualities
            val streamServers = document.select(".video-serv a, .server-list a, .quality-option")
            if (streamServers.isNotEmpty()) {
                append("\n📡 **السيرفرات المتاحة:**\n")
                streamServers.forEachIndexed { index, server ->
                    val serverName = server.text().trim().ifBlank { "السيرفر ${index + 1}" }
                    append("• $serverName\n")
                }
            }
            
            // Extract links for streaming
            val streamLinks = extractStreamLinks(document, actualUrl)
            if (streamLinks.isNotEmpty()) {
                append("\n🔗 **روابط البث:** ${streamLinks.size} رابط\n")
            }
        }
        
        // Extract all stream links from the page
        val streamLinks = extractStreamLinks(document, actualUrl)
        val data = if (streamLinks.isNotEmpty()) {
            streamLinks.joinToString("|||")
        } else {
            actualUrl
        }
        
        return newMovieLoadResponse(title, url, TvType.LiveStream, data) {
            this.posterUrl = poster
            this.plot = description.trim()
            
            // Build tags
            val tags = mutableListOf("كرة قدم", "رياضة", "مباراة")
            
            // Add tournament tags
            if (tournament.isNotBlank()) {
                tournament.split(",").map { it.trim() }.filter { it.isNotBlank() }.forEach { tag ->
                    if (!tags.contains(tag)) tags.add(tag)
                }
            }
            
            // Add team tags
            if (team1.isNotBlank()) tags.add(team1)
            if (team2.isNotBlank()) tags.add(team2)
            
            // Add status tags
            if (status.contains("live", true)) tags.add("بث مباشر")
            
            this.tags = tags
            
            // Recommendations
            val recommendations = document.select(".related-posts a, .widget a, .more-matches a")
                .mapNotNull { link ->
                    val recTitle = link.text().trim()
                    val recHref = link.attr("href")
                    
                    if (recTitle.isNotBlank() && recHref.isNotBlank() && recTitle.length > 3) {
                        newMovieSearchResponse(recTitle, fixUrl(recHref), TvType.LiveStream)
                    } else null
                }.take(5)
            
            this.recommendations = recommendations
        }
    }
    
    private suspend fun extractStreamLinks(document: Element, referer: String): List<String> {
        val links = mutableSetOf<String>()
        
        // Method 1: Direct iframes (most common for sports streams)
        document.select("iframe[src]").forEach { iframe ->
            val src = iframe.attr("src").trim()
            if (src.isNotBlank()) {
                // Try to extract from iframe content
                val iframeLinks = extractStreamsFromIframe(src, referer)
                links.addAll(iframeLinks)
                
                // Also add the iframe URL itself
                if (src.contains("stream") || src.contains("m3u8") || src.contains("live")) {
                    links.add(src)
                }
            }
        }
        
        // Method 2: Video elements
        document.select("video source[src], video[src]").forEach { video ->
            val src = video.attr("src").trim()
            if (src.isNotBlank() && (src.contains("m3u8") || src.contains("mp4"))) {
                links.add(src)
            }
        }
        
        // Method 3: Links with streaming keywords
        document.select("a[href*='stream'], a[href*='watch'], a[href*='live'], a[href*='m3u8']").forEach { link ->
            val href = link.attr("href").trim()
            if (href.isNotBlank() && href.startsWith("http")) {
                links.add(href)
            }
        }
        
        // Method 4: Extract from JavaScript
        document.select("script").forEach { script ->
            val scriptText = script.html()
            
            // Common patterns for streaming URLs
            val patterns = listOf(
                Regex("""['"](https?://[^'"]*\.m3u8[^'"]*)['"]"""),
                Regex("""['"](https?://[^'"]*stream[^'"]*)['"]"""),
                Regex("""src\s*[:=]\s*['"](https?://[^'"]+)['"]"""),
                Regex("""['"](https?://[^'"]*\.mp4[^'"]*)['"]"""),
                Regex("""file\s*[:=]\s*['"](https?://[^'"]+\.m3u8)['"]"""),
                Regex("""hls\.src\s*=\s*['"]([^'"]+)['"]"""),
                Regex("""player\.src\s*=\s*['"]([^'"]+)['"]""")
            )
            
            patterns.forEach { pattern ->
                pattern.findAll(scriptText).forEach { match ->
                    val url = match.groupValues[1]
                    if (url.isNotBlank() && (url.contains("m3u8") || url.contains("stream"))) {
                        links.add(url)
                    }
                }
            }
        }
        
        return links.toList()
    }
    
    private suspend fun extractStreamsFromIframe(iframeSrc: String, referer: String): List<String> {
        val links = mutableSetOf<String>()
        
        try {
            val iframeDoc = app.get(iframeSrc, referer = referer, headers = getHeaders()).document
            
            // Look for video elements in iframe
            iframeDoc.select("video source[src], video[src]").forEach { source ->
                val videoUrl = source.attr("src").trim()
                if (videoUrl.isNotBlank()) {
                    links.add(videoUrl)
                }
            }
            
            // Look for nested iframes
            iframeDoc.select("iframe[src]").forEach { nestedIframe ->
                val nestedSrc = nestedIframe.attr("src").trim()
                if (nestedSrc.isNotBlank()) {
                    links.addAll(extractStreamsFromIframe(nestedSrc, iframeSrc))
                }
            }
            
            // Extract from scripts in iframe
            iframeDoc.select("script").forEach { script ->
                val scriptText = script.html()
                
                // Look for m3u8 URLs
                Regex("""['"](https?://[^'"]*\.m3u8[^'"]*)['"]""").findAll(scriptText).forEach { match ->
                    val url = match.groupValues[1]
                    if (url.isNotBlank()) {
                        links.add(url)
                    }
                }
            }
            
        } catch (e: Exception) {
            // If we can't load the iframe, just return the iframe URL itself
            links.add(iframeSrc)
        }
        
        return links.toList()
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
                    // Try to load extractor first
                    loadExtractor(streamUrl, mainUrl, subtitleCallback, callback)
                    foundLinks = true
                } catch (e: Exception) {
                    // If extractor fails, check if it's a direct video URL
                    if (streamUrl.contains(".m3u8") || streamUrl.contains(".mp4")) {
                        val type = if (streamUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        callback.invoke(
                            newExtractorLink(
                                name,
                                "$name - بث مباشر",
                                streamUrl,
                                type
                            ) {
                                this.referer = mainUrl
                                this.quality = Qualities.Unknown.value
                            }
                        )
                        foundLinks = true
                    }
                }
            }
        } else {
            // Single URL - load the page and extract streams
            try {
                val doc = app.get(data, headers = getHeaders()).document
                val streamLinks = extractStreamLinks(doc, data)
                
                streamLinks.forEach { streamUrl ->
                    try {
                        loadExtractor(streamUrl, data, subtitleCallback, callback)
                        foundLinks = true
                    } catch (e: Exception) {
                        // Direct video fallback
                        if (streamUrl.contains(".m3u8") || streamUrl.contains(".mp4")) {
                            val type = if (streamUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            callback.invoke(
                                newExtractorLink(
                                    name,
                                    "$name - بث مباشر",
                                    streamUrl,
                                    type
                                ) {
                                    this.referer = data
                                    this.quality = Qualities.Unknown.value
                                }
                            )
                            foundLinks = true
                        }
                    }
                }
                
            } catch (e: Exception) {
                // If we can't load the page, try the URL directly as a stream
                if (data.contains("stream") || data.contains("m3u8")) {
                    try {
                        loadExtractor(data, mainUrl, subtitleCallback, callback)
                        foundLinks = true
                    } catch (e: Exception) {
                        // Last resort: check if it's a direct video URL
                        if (data.contains(".m3u8") || data.contains(".mp4")) {
                            val type = if (data.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            callback.invoke(
                                newExtractorLink(
                                    name,
                                    "$name - بث مباشر",
                                    data,
                                    type
                                ) {
                                    this.referer = mainUrl
                                    this.quality = Qualities.Unknown.value
                                }
                            )
                            foundLinks = true
                        }
                    }
                }
            }
        }
        
        return foundLinks
    }
    
    private fun getHeaders(): Map<String, String> {
        return mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
            "Accept-Language" to "ar,en-US;q=0.7,en;q=0.3",
            "Accept-Encoding" to "gzip, deflate, br",
            "Connection" to "keep-alive",
            "Upgrade-Insecure-Requests" to "1",
            "Referer" to mainUrl
        )
    }
    
    private fun fixUrl(url: String): String {
        if (url.isBlank()) return ""
        return when {
            url.startsWith("http") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "$mainUrl$url"
            else -> "$mainUrl/$url"
        }
    }
}
