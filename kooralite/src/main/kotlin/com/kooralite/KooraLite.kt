package com.kooralite

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.util.*

class KooraLite : MainAPI() {
    override var mainUrl = "https://www.kooralite.live"
    override var name = "KooraLite - كورة لايت"
    override var lang = "ar"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.LiveStream)
    override val instantLinkLoading = true
    
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
        
        return newMovieSearchResponse(enhancedTitle, href, TvType.LiveStream) {
            this.posterUrl = poster
        }
    }
    
    // Article item extraction
    private fun Element.toArticleSearchResponse(): SearchResponse? {
        val link = selectFirst("a")?.attr("href") ?: return null
        val href = fixUrl(link)
        
        val title = selectFirst(".gr-title, h3")?.text()?.trim() ?: return null
        
        val poster = selectFirst(".gr-img")?.attr("data-src")?.let { fixUrl(it) }
        
        return newMovieSearchResponse(title, href, TvType.LiveStream) {
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
                    items.add(newMovieSearchResponse(text, fullUrl, TvType.LiveStream))
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
            
            // Extract stream servers from iframes
            val iframes = document.select("iframe[src]")
            if (iframes.isNotEmpty()) {
                append("\n📡 السيرفرات المتاحة:\n")
                iframes.forEachIndexed { index, iframe ->
                    val src = iframe.attr("src").trim()
                    if (src.isNotBlank()) {
                        append("• سيرفر ${index + 1}\n")
                    }
                }
            }
        }
        
        // Extract stream links - focus on alkoora.live iframes
        val streamLinks = mutableListOf<String>()
        
        // Look for alkoora.live iframes (primary stream source)
        document.select("iframe[src*='alkoora.live']").forEach { iframe ->
            val src = iframe.attr("src").trim()
            if (src.isNotBlank()) {
                streamLinks.add(src)
            }
        }
        
        // Also look for other iframes as fallback
        if (streamLinks.isEmpty()) {
            document.select("iframe[src]").forEach { iframe ->
                val src = iframe.attr("src").trim()
                if (src.isNotBlank() && src.contains("stream|watch|video", true)) {
                    streamLinks.add(src)
                }
            }
        }
        
        // Look for direct stream-in.live links
        if (streamLinks.isEmpty() && url.contains("stream-in.live")) {
            streamLinks.add(url)
        }
        
        val data = if (streamLinks.isNotEmpty()) {
            streamLinks.joinToString("|||")
        } else {
            url
        }
        
        return newMovieLoadResponse(title, url, TvType.LiveStream, data) {
            this.posterUrl = poster
            this.plot = description.ifBlank { "مباراة كرة قدم مباشرة" }
            this.year = Calendar.getInstance().get(Calendar.YEAR)
            this.tags = listOf("كرة قدم", "رياضة", "بث مباشر")
            
            // Recommendations
            val recommendations = document.select(".related-posts a, .widget a")
                .mapNotNull { link ->
                    val recTitle = link.text().trim()
                    val recHref = link.attr("href")
                    
                    if (recTitle.isNotBlank() && recHref.isNotBlank() && recHref.contains("/match/")) {
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
            val streamLinks = data.split("|||").filter { it.isNotBlank() }
            
            streamLinks.forEachIndexed { index, streamUrl ->
                try {
                    // Check if it's an alkoora.live player
                    if (streamUrl.contains("alkoora.live")) {
                        handleAlkooraStream(streamUrl, index, callback)
                        foundLinks = true
                    } else {
                        // Try Cloudstream extractors for other URLs
                        loadExtractor(streamUrl, mainUrl, subtitleCallback, callback)
                        foundLinks = true
                    }
                } catch (e: Exception) {
                    // If extractor fails, check if it's a direct video URL
                    handleDirectStream(streamUrl, index, callback)
                    foundLinks = true
                }
            }
        } else {
            // Single URL - try to extract streams
            try {
                // Check if it's a direct alkoora stream
                if (data.contains("alkoora.live")) {
                    handleAlkooraStream(data, 0, callback)
                    foundLinks = true
                } else {
                    // Load the page and extract iframes
                    val doc = app.get(data, headers = getHeaders()).document
                    
                    // Look for alkoora.live iframes first
                    doc.select("iframe[src*='alkoora.live']").forEach { iframe ->
                        val src = iframe.attr("src").trim()
                        if (src.isNotBlank()) {
                            handleAlkooraStream(src, 0, callback)
                            foundLinks = true
                        }
                    }
                    
                    // Also try other iframes
                    if (!foundLinks) {
                        doc.select("iframe[src]").forEach { iframe ->
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
                    }
                }
            } catch (e: Exception) {
                // If everything fails, try the URL directly
                handleDirectStream(data, 0, callback)
                foundLinks = true
            }
        }
        
        return foundLinks
    }
    
    private suspend fun handleAlkooraStream(
        streamUrl: String,
        serverIndex: Int,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            // Try to load the alkoora player page
            val playerDoc = app.get(streamUrl, headers = getHeaders()).document
            
            // Look for m3u8 URLs in scripts
            playerDoc.select("script").forEach { script ->
                val scriptText = script.html()
                
                // Pattern for m3u8 URLs
                val m3u8Pattern = Regex("""['"](https?://[^'"]*\.m3u8[^'"]*)['"]""")
                m3u8Pattern.findAll(scriptText).forEach { match ->
                    val m3u8Url = match.groupValues[1].trim()
                    if (m3u8Url.isNotBlank()) {
                        callback.invoke(
                            newExtractorLink(
                                name,
                                "$name - سيرفر ${serverIndex + 1}",
                                m3u8Url,
                                ExtractorLinkType.HLS
                            ) {
                                this.referer = streamUrl
                                this.quality = Qualities.Unknown.value
                                this.headers = getHeaders()
                            }
                        )
                    }
                }
                
                // Pattern for stream master URLs
                val streamPattern = Regex("""['"](https?://[^'"]*master\.m3u8[^'"]*)['"]""")
                streamPattern.findAll(scriptText).forEach { match ->
                    val masterUrl = match.groupValues[1].trim()
                    if (masterUrl.isNotBlank()) {
                        callback.invoke(
                            newExtractorLink(
                                name,
                                "$name - سيرفر ${serverIndex + 1} (HD)",
                                masterUrl,
                                ExtractorLinkType.HLS
                            ) {
                                this.referer = streamUrl
                                this.quality = Qualities.Unknown.value
                                this.headers = getHeaders()
                            }
                        )
                    }
                }
            }
            
            // Also check for direct video elements
            playerDoc.select("video source[src]").forEach { source ->
                val src = source.attr("src").trim()
                if (src.isNotBlank() && src.contains(".m3u8")) {
                    callback.invoke(
                        newExtractorLink(
                            name,
                            "$name - سيرفر ${serverIndex + 1}",
                            src,
                            ExtractorLinkType.HLS
                        ) {
                            this.referer = streamUrl
                            this.quality = Qualities.Unknown.value
                            this.headers = getHeaders()
                        }
                    )
                }
            }
            
        } catch (e: Exception) {
            // If we can't extract m3u8, try the alkoora URL directly
            // Sometimes alkoora redirects to actual stream
            try {
                loadExtractor(streamUrl, mainUrl, {}) { link ->
                    callback.invoke(link)
                }
            } catch (e: Exception) {
                // Last resort: create a direct link to the iframe
                callback.invoke(
                    newExtractorLink(
                        name,
                        "$name - سيرفر ${serverIndex + 1}",
                        streamUrl,
                        ExtractorLinkType.HLS
                    ) {
                        this.referer = mainUrl
                        this.quality = Qualities.Unknown.value
                        this.headers = getHeaders()
                    }
                )
            }
        }
    }
    
    private fun handleDirectStream(
        streamUrl: String,
        serverIndex: Int,
        callback: (ExtractorLink) -> Unit
    ) {
        // Check if it's a direct video URL
        when {
            streamUrl.contains(".m3u8", true) -> {
                callback.invoke(
                    newExtractorLink(
                        name,
                        "$name - سيرفر ${serverIndex + 1}",
                        streamUrl,
                        ExtractorLinkType.HLS
                    ) {
                        this.referer = mainUrl
                        this.quality = Qualities.Unknown.value
                        this.headers = getHeaders()
                    }
                )
            }
            
            streamUrl.contains(".mp4", true) -> {
                callback.invoke(
                    newExtractorLink(
                        name,
                        "$name - سيرفر ${serverIndex + 1}",
                        streamUrl,
                        ExtractorLinkType.VIDEO
                    ) {
                        this.referer = mainUrl
                        this.quality = Qualities.Unknown.value
                        this.headers = getHeaders()
                    }
                )
            }
            
            streamUrl.contains("youtube.com", true) -> {
                // YouTube streams - will be handled by Cloudstream's YouTube extractor
                callback.invoke(
                    newExtractorLink(
                        name,
                        "$name - سيرفر ${serverIndex + 1} (YouTube)",
                        streamUrl,
                        ExtractorLinkType.HLS
                    ) {
                        this.referer = mainUrl
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
        }
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
            else -> "$mainUrl/$url"
        }
    }
}
