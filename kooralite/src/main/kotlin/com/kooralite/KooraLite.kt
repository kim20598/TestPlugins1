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
    override val supportedTypes = setOf(TvType.LiveStream)
    override val instantLinkLoading = true
    override val useMobileUserAgent = false
    
    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }
    
    // Match item extraction - enhanced for better detection
    private fun Element.toMatchSearchResponse(): SearchResponse? {
        try {
            val link = selectFirst("a")?.attr("href") ?: return null
            val href = fixUrl(link)
            
            // Only accept match links or stream links
            if (!href.contains("/match/") && 
                !href.contains("stream-in.live") && 
                !href.contains("stream")) {
                return null
            }
            
            // Try to get team names from different possible structures
            val team1 = selectFirst(".MT_Team.TM1 .TM_Name, .team1 .name, [class*='team1'] [class*='name']")?.text()?.trim()
                ?: selectFirst(".MT_Team:first-child .TM_Name")?.text()?.trim()
                ?: return null
            
            val team2 = selectFirst(".MT_Team.TM2 .TM_Name, .team2 .name, [class*='team2'] [class*='name']")?.text()?.trim()
                ?: selectFirst(".MT_Team:last-child .TM_Name")?.text()?.trim()
                ?: return null
            
            val title = "$team1 vs $team2"
            
            // Get match status from class or data attribute
            val statusClass = classNames().firstOrNull { 
                it.contains("live", true) || 
                it.contains("finished", true) || 
                it.contains("comming", true) || 
                it.contains("not-started", true)
            } ?: ""
            
            val statusText = when {
                statusClass.contains("live", true) -> "🔴 مباشر"
                statusClass.contains("finished", true) -> "✅ انتهت"
                statusClass.contains("comming", true) -> "⏳ قادمة"
                else -> "⚽"
            }
            
            // Get match time from various possible selectors
            val time = selectFirst(".MT_Time, .match-time, .time, .datetime")?.text()?.trim() ?: ""
            
            // Get tournament/league info
            val tournament = selectFirst(".MT_Info li:last-child span, .league, .tournament, .competition")?.text()?.trim() ?: ""
            
            // Get team logos - try multiple attribute names
            val team1Logo = selectFirst(".TM1 img, .team1 img")?.let { img ->
                img.attr("data-src").ifBlank { img.attr("src") }?.let { fixUrl(it) }
            }
            
            val team2Logo = selectFirst(".TM2 img, .team2 img")?.let { img ->
                img.attr("data-src").ifBlank { img.attr("src") }?.let { fixUrl(it) }
            }
            
            // Choose poster
            val poster = team1Logo ?: team2Logo ?: selectFirst("img")?.attr("src")?.let { fixUrl(it) }
            
            // Enhanced title with all relevant info
            val enhancedTitle = buildString {
                if (statusText.isNotBlank()) {
                    append("$statusText ")
                }
                append(title)
                if (tournament.isNotBlank()) {
                    append(" - $tournament")
                }
                if (time.isNotBlank()) {
                    append(" ($time)")
                }
            }
            
            return newMovieSearchResponse(enhancedTitle, href, TvType.LiveStream) {
                this.posterUrl = poster
                this.posterHeaders = getHeaders()
            }
        } catch (e: Exception) {
            return null
        }
    }
    
    // Article item extraction
    private fun Element.toArticleSearchResponse(): SearchResponse? {
        try {
            val link = selectFirst("a")?.attr("href") ?: return null
            val href = fixUrl(link)
            
            val title = selectFirst(".gr-title, .post-title, h3, h2, h4")?.text()?.trim() ?: return null
            
            val poster = selectFirst(".gr-img, img, .post-thumbnail img")?.let { img ->
                img.attr("data-src").ifBlank { img.attr("src") }?.let { fixUrl(it) }
            }
            
            return newMovieSearchResponse(title, href, TvType.LiveStream) {
                this.posterUrl = poster
                this.posterHeaders = getHeaders()
            }
        } catch (e: Exception) {
            return null
        }
    }
    
    override val mainPage = mainPageOf(
        "$mainUrl/" to "الرئيسية",
        "$mainUrl/matches-today/" to "مباريات اليوم",
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
        val document = app.get(url, headers = getHeaders()).document
        
        val items = mutableListOf<SearchResponse>()
        
        // Prioritize matches on home and match pages
        if (request.data.contains("matches-") || request.data == "$mainUrl/") {
            // Look for matches in multiple possible containers
            document.select(".AY_Match, .match-item, .match-card, .fixture").forEach { match ->
                match.toMatchSearchResponse()?.let { items.add(it) }
            }
        }
        
        // If no matches found or it's a category page, look for articles
        if (items.isEmpty() || request.data.contains("category/")) {
            document.select(".gr-item, .post-item, article, .news-item").forEach { article ->
                article.toArticleSearchResponse()?.let { items.add(it) }
            }
        }
        
        // Fallback: direct links for matches
        if (items.isEmpty()) {
            document.select("a[href*='/match/'], a[href*='stream'], a[href*='watch']").forEach { link ->
                val href = link.attr("href")
                val text = link.text().trim()
                
                if (text.isNotBlank() && href.isNotBlank() && href.contains(Regex("match|stream|watch"))) {
                    val fullUrl = fixUrl(href)
                    val title = if (text.length > 3) text else "مباراة كرة قدم"
                    items.add(newMovieSearchResponse(title, fullUrl, TvType.LiveStream))
                }
            }
        }
        
        return newHomePageResponse(
            request.name, 
            items.distinctBy { it.url }.sortedByDescending { 
                // Sort live matches first, then upcoming, then finished
                when {
                    it.name?.contains("🔴") == true -> 1
                    it.name?.contains("⏳") == true -> 2
                    it.name?.contains("✅") == true -> 3
                    else -> 4
                }
            },
            hasNext = items.isNotEmpty() && document.select("a.next, .pagination a, .nav-links a").isNotEmpty()
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
            document.select(".AY_Match, .match-item").forEach { match ->
                match.toMatchSearchResponse()?.let { results.add(it) }
            }
            
            // Look for articles
            document.select(".gr-item, article").forEach { article ->
                article.toArticleSearchResponse()?.let { results.add(it) }
            }
            
            // Direct links fallback
            document.select("a[href*='/match/']").forEach { link ->
                val href = link.attr("href")
                val text = link.text().trim()
                
                if (text.isNotBlank() && href.isNotBlank()) {
                    val fullUrl = fixUrl(href)
                    results.add(newMovieSearchResponse(text, fullUrl, TvType.LiveStream))
                }
            }
            
            results.distinctBy { it.url }
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = getHeaders()).document
        
        // Extract title with fallbacks
        val title = document.selectFirst("h1.entry-title, h1.title, h1, .post-title, .match-title")?.text()?.trim()
            ?: "مباراة كرة قدم"
        
        // Extract poster from multiple possible sources
        val poster = document.selectFirst("meta[property='og:image']")?.attr("content")?.let { fixUrl(it) }
            ?: document.selectFirst(".post-thumbnail img, .featured-image img, img.wp-post-image")?.attr("src")?.let { fixUrl(it) }
            ?: document.selectFirst("img")?.attr("src")?.takeIf { it.contains("logo|team|stadium", RegexOption.IgnoreCase) }?.let { fixUrl(it) }
        
        // Extract description from match info
        val description = buildString {
            // Extract match details from various table structures
            val matchTables = document.select("table, .match-info, .fixture-details")
            if (matchTables.isNotEmpty()) {
                append("📋 معلومات المباراة:\n")
                
                matchTables.forEach { table ->
                    table.select("tr, .info-row").forEach { row ->
                        val header = row.select("th, .label, .key").text().trim()
                        val value = row.select("td, .value, .data").text().trim()
                        
                        if (header.isNotBlank() && value.isNotBlank()) {
                            append("• $header: $value\n")
                        }
                    }
                }
            }
            
            // Add stream servers if available
            val servers = document.select(".video-serv a, .stream-server, .server-option")
            if (servers.isNotEmpty()) {
                append("\n📡 السيرفرات المتاحة:\n")
                servers.forEachIndexed { index, server ->
                    val serverName = server.text().trim()
                    if (serverName.isNotBlank()) {
                        val serverNum = index + 1
                        append("• سيرفر $serverNum: $serverName\n")
                    }
                }
            }
            
            // Add any additional info from paragraphs
            val paragraphs = document.select(".entry-content p, .post-content p, .description p")
            if (paragraphs.isNotEmpty() && length < 100) {
                append("\n📝 التفاصيل:\n")
                paragraphs.take(3).forEach { p ->
                    val text = p.text().trim()
                    if (text.length > 20) {
                        append("• $text\n")
                    }
                }
            }
        }
        
        // Extract stream links from various sources
        val streamLinks = mutableListOf<String>()
        
        // Look for iframes (primary source for sports streams)
        document.select("iframe[src], embed[src]").forEach { iframe ->
            val src = iframe.attr("src").trim()
            if (src.isNotBlank() && (src.contains("stream|watch|video", RegexOption.IgnoreCase) || 
                src.contains("youtube|dailymotion|streamin|stream-in", RegexOption.IgnoreCase))) {
                streamLinks.add(src)
            }
        }
        
        // Look for video elements with sources
        document.select("video source[src], video[src], [data-src*='stream']").forEach { video ->
            val src = video.attr("src").ifBlank { video.attr("data-src") }.trim()
            if (src.isNotBlank() && (src.contains("m3u8|mp4|stream", RegexOption.IgnoreCase))) {
                streamLinks.add(src)
            }
        }
        
        // Look for streaming links in scripts
        document.select("script").forEach { script ->
            val scriptText = script.html()
            
            // Look for various stream URL patterns
            val patterns = listOf(
                Regex("""['"](https?://[^'"]*\.m3u8[^'"]*)['"]"""),
                Regex("""['"](https?://[^'"]*stream[^'"]*)['"]"""),
                Regex("""['"](https?://[^'"]*watch[^'"]*)['"]"""),
                Regex("""src\s*[:=]\s*['"]([^'"]+)['"]""")
            )
            
            patterns.forEach { pattern ->
                pattern.findAll(scriptText).forEach { match ->
                    val url = match.groupValues.lastOrNull()?.trim()
                    if (url != null && url.isNotBlank() && 
                        url.startsWith("http") && 
                        url.contains("stream|m3u8|video", RegexOption.IgnoreCase)) {
                        streamLinks.add(url)
                    }
                }
            }
        }
        
        // If no stream links found but URL is a stream page itself
        if (streamLinks.isEmpty() && url.contains("stream-in.live|stream|watch", RegexOption.IgnoreCase)) {
            streamLinks.add(url)
        }
        
        val data = if (streamLinks.isNotEmpty()) {
            streamLinks.distinct().joinToString("|||")
        } else {
            url
        }
        
        return newMovieLoadResponse(title, url, TvType.LiveStream, data) {
            this.posterUrl = poster
            this.plot = description.ifBlank { "مباراة كرة قدم مباشرة - شاهد البث الحي" }
            this.year = Calendar.getInstance().get(Calendar.YEAR)
            this.tags = listOf("كرة قدم", "رياضة", "بث مباشر", "مباريات")
            
            // Extract recommendations/suggested matches
            val recommendations = document.select(".related-posts a, .widget a, .suggested-matches a")
                .mapNotNull { link ->
                    val recTitle = link.text().trim()
                    val recHref = link.attr("href")
                    
                    if (recTitle.length > 3 && recHref.isNotBlank() && recHref.contains("/match/")) {
                        newMovieSearchResponse(recTitle, fixUrl(recHref), TvType.LiveStream)
                    } else null
                }.take(5)
            
            this.recommendations = recommendations
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
            val streamLinks = data.split("|||").filter { it.isNotBlank() }.distinct()
            
            streamLinks.forEachIndexed { index, streamUrl ->
                try {
                    // Add headers for the stream
                    val streamHeaders = getHeaders().toMutableMap().apply {
                        put("Referer", mainUrl)
                    }
                    
                    // Try to load extractor first
                    loadExtractor(streamUrl, "$mainUrl/", subtitleCallback, callback)
                    foundLinks = true
                } catch (e: Exception) {
                    // If extractor fails, check if it's a direct video URL
                    val cleanUrl = streamUrl.trim()
                    when {
                        cleanUrl.contains(".m3u8", true) -> {
                            callback.invoke(
                                newExtractorLink(
                                    name,
                                    "$name - بث مباشر (سيرفر ${index + 1})",
                                    cleanUrl,
                                    ExtractorLinkType.HLS
                                ) {
                                    this.referer = mainUrl
                                    this.quality = Qualities.Unknown.value
                                    this.headers = getHeaders()
                                }
                            )
                            foundLinks = true
                        }
                        
                        cleanUrl.contains(".mp4", true) -> {
                            callback.invoke(
                                newExtractorLink(
                                    name,
                                    "$name - بث مباشر (سيرفر ${index + 1})",
                                    cleanUrl,
                                    ExtractorLinkType.VIDEO
                                ) {
                                    this.referer = mainUrl
                                    this.quality = Qualities.Unknown.value
                                    this.headers = getHeaders()
                                }
                            )
                            foundLinks = true
                        }
                        
                        cleanUrl.contains("youtube.com", true) -> {
                            // Handle YouTube links
                            loadExtractor(cleanUrl, mainUrl, subtitleCallback, callback)
                            foundLinks = true
                        }
                    }
                }
            }
        } else {
            // Single URL - try to extract streams from the page
            try {
                val doc = app.get(data, headers = getHeaders()).document
                
                // Look for iframes
                doc.select("iframe[src], embed[src]").forEach { iframe ->
                    val src = iframe.attr("src").trim()
                    if (src.isNotBlank()) {
                        try {
                            loadExtractor(src, data, subtitleCallback, callback)
                            foundLinks = true
                        } catch (e: Exception) {
                            // Ignore extractor failures
                        }
                    }
                }
                
                // Look for direct video sources
                doc.select("video source[src], video[src], [data-src*='.m3u8'], [data-src*='.mp4']").forEach { video ->
                    val src = video.attr("src").ifBlank { video.attr("data-src") }.trim()
                    if (src.isNotBlank() && src.startsWith("http")) {
                        val type = when {
                            src.contains(".m3u8", true) -> ExtractorLinkType.HLS
                            src.contains(".mp4", true) -> ExtractorLinkType.VIDEO
                            else -> ExtractorLinkType.VIDEO
                        }
                        
                        callback.invoke(
                            newExtractorLink(
                                name,
                                "$name - بث مباشر",
                                src,
                                type
                            ) {
                                this.referer = data
                                this.quality = Qualities.Unknown.value
                                this.headers = getHeaders()
                            }
                        )
                        foundLinks = true
                    }
                }
                
                // Look for streaming scripts
                doc.select("script").forEach { script ->
                    val scriptText = script.html()
                    
                    // Look for various stream patterns
                    val patterns = listOf(
                        Regex("""['"](https?://[^'"]*\.m3u8[^'"]*)['"]"""),
                        Regex("""['"](https?://[^'"]*\.mp4[^'"]*)['"]"""),
                        Regex("""src\s*:\s*['"]([^'"]+)['"]"""),
                        Regex("""file\s*:\s*['"]([^'"]+)['"]""")
                    )
                    
                    patterns.forEach { pattern ->
                        pattern.findAll(scriptText).forEach { match ->
                            val url = match.groupValues.lastOrNull()?.trim()
                            if (url != null && url.isNotBlank() && url.startsWith("http")) {
                                try {
                                    loadExtractor(url, data, subtitleCallback, callback)
                                    foundLinks = true
                                } catch (e: Exception) {
                                    // Try as direct link
                                    if (url.contains(".m3u8", true) || url.contains(".mp4", true)) {
                                        val type = if (url.contains(".m3u8", true)) ExtractorLinkType.HLS else ExtractorLinkType.VIDEO
                                        callback.invoke(
                                            newExtractorLink(
                                                name,
                                                "$name - بث مباشر",
                                                url,
                                                type
                                            ) {
                                                this.referer = data
                                                this.quality = Qualities.Unknown.value
                                                this.headers = getHeaders()
                                            }
                                        )
                                        foundLinks = true
                                    }
                                }
                            }
                        }
                    }
                }
                
            } catch (e: Exception) {
                // If page loading fails, try the URL directly as a stream
                if (data.contains("stream|m3u8|mp4", RegexOption.IgnoreCase)) {
                    try {
                        loadExtractor(data, mainUrl, subtitleCallback, callback)
                        foundLinks = true
                    } catch (e: Exception) {
                        // Last attempt: treat as direct HLS/MP4
                        when {
                            data.contains(".m3u8", true) -> {
                                callback.invoke(
                                    newExtractorLink(
                                        name,
                                        "$name - بث مباشر",
                                        data,
                                        ExtractorLinkType.HLS
                                    ) {
                                        this.referer = mainUrl
                                        this.quality = Qualities.Unknown.value
                                        this.headers = getHeaders()
                                    }
                                )
                                foundLinks = true
                            }
                            
                            data.contains(".mp4", true) -> {
                                callback.invoke(
                                    newExtractorLink(
                                        name,
                                        "$name - بث مباشر",
                                        data,
                                        ExtractorLinkType.VIDEO
                                    ) {
                                        this.referer = mainUrl
                                        this.quality = Qualities.Unknown.value
                                        this.headers = getHeaders()
                                    }
                                )
                                foundLinks = true
                            }
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
            "Referer" to mainUrl,
            "Sec-Fetch-Dest" to "document",
            "Sec-Fetch-Mode" to "navigate",
            "Sec-Fetch-Site" to "same-origin",
            "Cache-Control" to "max-age=0"
        )
    }
    
    private fun fixUrl(url: String): String {
        if (url.isBlank()) return ""
        return when {
            url.startsWith("http") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "$mainUrl$url"
            url.startsWith("./") -> "$mainUrl/${url.substring(2)}"
            else -> "$mainUrl/$url"
        }
    }
}
