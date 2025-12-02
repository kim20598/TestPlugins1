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
    
    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }
    
    // Match item extraction
    private fun Element.toMatchSearchResponse(): SearchResponse? {
        val link = selectFirst("a")?.attr("href") ?: return null
        val href = fixUrl(link)
        
        // Skip if not a match link
        if (!href.contains("/match/") && !href.contains("stream-in.live")) return null
        
        // Get team names
        val team1 = selectFirst(".MT_Team.TM1 .TM_Name")?.text()?.trim() ?: return null
        val team2 = selectFirst(".MT_Team.TM2 .TM_Name")?.text()?.trim() ?: return null
        
        val title = "$team1 vs $team2"
        
        // Get match status
        val statusClass = classNames().firstOrNull { 
            it in listOf("live", "finished", "comming-soon", "not-started") 
        } ?: ""
        
        val statusText = when (statusClass) {
            "live" -> "🔴 مباشر"
            "finished" -> "✅ انتهت"
            "comming-soon" -> "⏳ قادمة"
            else -> "⚽"
        }
        
        // Get match time
        val time = selectFirst(".MT_Time")?.text()?.trim() ?: ""
        
        // Get tournament/league
        val tournament = selectFirst(".MT_Info li:last-child span")?.text()?.trim() ?: ""
        
        // Get team logos
        val team1Logo = selectFirst(".TM1 img")?.attr("data-src")?.let { fixUrl(it) }
        val team2Logo = selectFirst(".TM2 img")?.attr("data-src")?.let { fixUrl(it) }
        
        // Choose poster (team1 logo first)
        val poster = team1Logo ?: team2Logo
        
        // Enhanced title
        val enhancedTitle = buildString {
            if (statusText.isNotBlank()) append("$statusText ")
            append(title)
            if (time.isNotBlank()) append(" ($time)")
        }
        
        return newMovieSearchResponse(enhancedTitle, href, TvType.Movie) {
            this.posterUrl = poster
        }
    }
    
    // Article item extraction
    private fun Element.toArticleSearchResponse(): SearchResponse? {
        val link = selectFirst("a")?.attr("href") ?: return null
        val href = fixUrl(link)
        
        val title = selectFirst(".gr-title, h3")?.text()?.trim() ?: return null
        
        val poster = selectFirst(".gr-img")?.attr("data-src")?.let { fixUrl(it) }
        
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = poster
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
        
        // For match pages, extract matches
        if (request.data.contains("matches-") || request.data == "$mainUrl/") {
            document.select(".AY_Match").forEach { match ->
                match.toMatchSearchResponse()?.let { items.add(it) }
            }
        }
        
        // For news/category pages, extract articles
        if (items.isEmpty() || request.data.contains("category/")) {
            document.select(".gr-item").forEach { article ->
                article.toArticleSearchResponse()?.let { items.add(it) }
            }
        }
        
        // Fallback: direct links
        if (items.isEmpty()) {
            document.select("a[href*='/match/'], a[href*='stream-in.live']").forEach { link ->
                val href = link.attr("href")
                val text = link.text().trim()
                
                if (text.isNotBlank() && href.isNotBlank()) {
                    val fullUrl = fixUrl(href)
                    items.add(newMovieSearchResponse(text, fullUrl, TvType.Movie))
                }
            }
        }
        
        return newHomePageResponse(
            request.name, 
            items.distinctBy { it.url },
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
            document.select(".AY_Match").forEach { match ->
                match.toMatchSearchResponse()?.let { results.add(it) }
            }
            
            // Look for articles
            document.select(".gr-item").forEach { article ->
                article.toArticleSearchResponse()?.let { results.add(it) }
            }
            
            // Direct links fallback
            document.select("a[href*='/match/']").forEach { link ->
                val href = link.attr("href")
                val text = link.text().trim()
                
                if (text.isNotBlank() && href.isNotBlank()) {
                    val fullUrl = fixUrl(href)
                    results.add(newMovieSearchResponse(text, fullUrl, TvType.Movie))
                }
            }
            
            results.distinctBy { it.url }
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = getHeaders()).document
        
        // Extract title
        val title = document.selectFirst("h1.entry-title, h1.title, h1")?.text()?.trim()
            ?: "مباراة كرة قدم"
        
        // Extract poster
        val poster = document.selectFirst("meta[property='og:image']")?.attr("content")?.let { fixUrl(it) }
            ?: document.selectFirst("img[src*='logo']")?.attr("src")?.let { fixUrl(it) }
        
        // Extract description from match info
        val description = buildString {
            // Extract match details from table
            val matchTable = document.select("table.table-bordered")
            if (matchTable.isNotEmpty()) {
                append("📋 معلومات المباراة:\n")
                
                matchTable.select("tr").forEach { row ->
                    val header = row.select("th").text().trim()
                    val value = row.select("td").text().trim()
                    
                    if (header.isNotBlank() && value.isNotBlank()) {
                        append("• $header: $value\n")
                    }
                }
            }
            
            // Add stream servers
            val servers = document.select(".video-serv a")
            if (servers.isNotEmpty()) {
                append("\n📡 السيرفرات المتاحة:\n")
                servers.forEachIndexed { index, server ->
                    val serverName = server.text().trim()
                    if (serverName.isNotBlank()) {
                        append("• $serverName\n")
                    }
                }
            }
        }
        
        // Extract stream links
        val streamLinks = mutableListOf<String>()
        
        // Look for iframes
        document.select("iframe[src]").forEach { iframe ->
            val src = iframe.attr("src").trim()
            if (src.isNotBlank()) {
                streamLinks.add(src)
            }
        }
        
        // Look for direct stream-in.live links
        if (url.contains("stream-in.live")) {
            streamLinks.add(url)
        }
        
        val data = if (streamLinks.isNotEmpty()) {
            streamLinks.joinToString("|||")
        } else {
            url
        }
        
        return newMovieLoadResponse(title, url, TvType.Movie, data) {
            this.posterUrl = poster
            this.plot = description.ifBlank { "مباراة كرة قدم مباشرة" }
            this.tags = listOf("كرة قدم", "رياضة", "بث مباشر")
            
            // Recommendations
            val recommendations = document.select(".related-posts a, .widget a")
                .mapNotNull { link ->
                    val recTitle = link.text().trim()
                    val recHref = link.attr("href")
                    
                    if (recTitle.isNotBlank() && recHref.isNotBlank()) {
                        newMovieSearchResponse(recTitle, fixUrl(recHref), TvType.Movie)
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
            val streamLinks = data.split("|||").filter { it.isNotBlank() }
            
            streamLinks.forEach { streamUrl ->
                try {
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
                
                // Look for iframes (common for sports streams)
                doc.select("iframe[src]").forEach { iframe ->
                    val src = iframe.attr("src").trim()
                    if (src.isNotBlank()) {
                        loadExtractor(src, data, subtitleCallback, callback)
                        foundLinks = true
                    }
                }
                
                // Look for video elements
                doc.select("video source[src], video[src]").forEach { video ->
                    val src = video.attr("src").trim()
                    if (src.isNotBlank() && (src.contains(".m3u8") || src.contains(".mp4"))) {
                        val type = if (src.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        callback.invoke(
                            newExtractorLink(
                                name,
                                "$name - بث مباشر",
                                src,
                                type
                            ) {
                                this.referer = data
                                this.quality = Qualities.Unknown.value
                            }
                        )
                        foundLinks = true
                    }
                }
                
                // Look for streaming scripts
                doc.select("script").forEach { script ->
                    val scriptText = script.html()
                    
                    // Look for m3u8 URLs
                    Regex("""['"](https?://[^'"]*\.m3u8[^'"]*)['"]""").findAll(scriptText).forEach { match ->
                        val url = match.groupValues[1]
                        if (url.isNotBlank()) {
                            loadExtractor(url, data, subtitleCallback, callback)
                            foundLinks = true
                        }
                    }
                    
                    // Look for stream URLs
                    Regex("""['"](https?://[^'"]*stream[^'"]*)['"]""").findAll(scriptText).forEach { match ->
                        val url = match.groupValues[1]
                        if (url.isNotBlank()) {
                            loadExtractor(url, data, subtitleCallback, callback)
                            foundLinks = true
                        }
                    }
                }
                
            } catch (e: Exception) {
                // If page loading fails, try the URL directly if it looks like a stream
                if (data.contains("stream") || data.contains("m3u8")) {
                    try {
                        loadExtractor(data, mainUrl, subtitleCallback, callback)
                        foundLinks = true
                    } catch (e: Exception) {
                        // Ignore
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
            "Sec-Fetch-Site" to "same-origin"
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
