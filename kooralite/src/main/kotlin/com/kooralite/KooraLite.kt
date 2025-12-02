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
    
    // Debug function to log HTML structure
    private fun debugHtml(document: Element, pageName: String) {
        println("=== DEBUG $pageName ===")
        println("Page URL: ${document.location()}")
        println("--- Top level divs ---")
        document.select("div").take(20).forEachIndexed { index, div ->
            val classes = div.classNames().joinToString(" ")
            val id = div.id()
            if (classes.isNotBlank() || id.isNotBlank()) {
                println("Div $index: classes='$classes' id='$id'")
            }
        }
        println("--- Articles ---")
        document.select("article").forEachIndexed { index, article ->
            println("Article $index: ${article.className()}")
        }
        println("--- All links ---")
        document.select("a").take(30).forEachIndexed { index, link ->
            val href = link.attr("href")
            val text = link.text().trim()
            if (text.isNotBlank() && href.contains("/match/")) {
                println("Link $index: '$text' -> $href")
            }
        }
        println("=== END DEBUG ===")
    }
    
    private fun Element.toSearchResponse(): SearchResponse? {
        // Try multiple possible selectors for matches
        val link = selectFirst("a")?.attr("href") ?: return null
        val href = fixUrl(link)
        
        // Skip non-match links
        if (!href.contains("/match/") && !href.contains("stream-in.live")) {
            return null
        }
        
        // Try to extract title from various elements
        val title = selectFirst("h3, h2, .title, .entry-title, .match-title, .team-name")?.text()?.trim()
            ?: ownText().trim()
            ?: attr("title").trim()
        
        if (title.isBlank()) return null
        
        // Try to extract poster/image
        val poster = selectFirst("img")?.let { img ->
            img.attr("data-src").ifBlank { img.attr("src") }
        }?.takeIf { it.isNotBlank() }?.let { fixUrl(it) }
            ?: selectFirst(".logo, .team-logo, .thumbnail")?.attr("src")?.let { fixUrl(it) }
        
        // Check if it's a live match
        val isLive = text().contains("مباشر", true) || 
                     classNames().any { it.contains("live", true) } ||
                     href.contains("live", true)
        
        // Create enhanced title
        val enhancedTitle = if (isLive) "🔴 $title" else title
        
        return newMovieSearchResponse(enhancedTitle, href, TvType.Movie) {
            this.posterUrl = poster
        }
    }
    
    override val mainPage = mainPageOf(
        "$mainUrl/" to "الرئيسية",
        "$mainUrl/matches-today/" to "مباريات اليوم",
        "$mainUrl/matches-live/" to "المباريات الحية",
        "$mainUrl/category/premier-league/" to "الدوري الإنجليزي",
        "$mainUrl/category/la-liga/" to "الدوري الإسباني",
        "$mainUrl/category/serie-a/" to "الدوري الإيطالي",
        "$mainUrl/category/bundesliga/" to "الدوري الألماني",
        "$mainUrl/category/champions-league/" to "دوري الأبطال",
        "$mainUrl/category/europa-league/" to "الدوري الأوروبي",
        "$mainUrl/category/world-cup/" to "كأس العالم"
    )
    
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page > 1) {
            if (request.data.contains("?")) "${request.data}&page=$page" 
            else "${request.data}page/$page/"
        } else {
            request.data
        }
        
        println("=== Loading URL: $url ===")
        val document = app.get(url, headers = getHeaders()).document
        
        // Debug the HTML structure
        debugHtml(document, request.name)
        
        val items = mutableListOf<SearchResponse>()
        
        // Method 1: Look for any divs that might contain match info
        document.select("div").forEach { div ->
            // Check if this div looks like it contains match info
            val hasMatchLink = div.select("a[href*='/match/']").isNotEmpty() ||
                              div.select("a[href*='stream-in.live']").isNotEmpty()
            
            if (hasMatchLink) {
                div.toSearchResponse()?.let { items.add(it) }
            }
        }
        
        // Method 2: Look for articles
        if (items.isEmpty()) {
            document.select("article, .post, .item, .match-item").forEach { element ->
                element.toSearchResponse()?.let { items.add(it) }
            }
        }
        
        // Method 3: Direct links
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
        
        // Remove duplicates
        val uniqueItems = items.distinctBy { it.url }
        
        println("=== Found ${uniqueItems.size} items for ${request.name} ===")
        uniqueItems.forEachIndexed { index, item ->
            println("Item $index: ${item.name} -> ${item.url}")
        }
        
        return newHomePageResponse(
            request.name, 
            uniqueItems, 
            hasNext = uniqueItems.isNotEmpty() && document.select("a.next, .pagination a").isNotEmpty()
        )
    }
    
    override suspend fun search(query: String): List<SearchResponse> {
        if (query.length < 2) return emptyList()
        
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val searchUrl = "$mainUrl/?s=$encodedQuery"
        
        println("=== Searching for: $query ===")
        
        return try {
            val document = app.get(searchUrl, headers = getHeaders()).document
            val results = mutableListOf<SearchResponse>()
            
            // Look for matches in search results
            document.select("article, .post, .search-result, div").forEach { element ->
                element.toSearchResponse()?.let { results.add(it) }
            }
            
            // Also look for direct links
            document.select("a[href*='/match/']").forEach { link ->
                val href = link.attr("href")
                val text = link.text().trim()
                
                if (text.isNotBlank() && href.isNotBlank()) {
                    val fullUrl = fixUrl(href)
                    results.add(newMovieSearchResponse(text, fullUrl, TvType.Movie))
                }
            }
            
            println("=== Found ${results.size} search results ===")
            results.distinctBy { it.url }
        } catch (e: Exception) {
            println("=== Search error: ${e.message} ===")
            emptyList()
        }
    }
    
    override suspend fun load(url: String): LoadResponse {
        println("=== Loading match page: $url ===")
        
        val document = app.get(url, headers = getHeaders()).document
        
        // Extract title
        val title = document.selectFirst("h1.entry-title, h1.title, h1")?.text()?.trim()
            ?: document.selectFirst("title")?.text()?.trim()
            ?: "مباراة كرة قدم"
        
        // Extract poster/image
        val poster = document.selectFirst("meta[property='og:image']")?.attr("content")?.let { fixUrl(it) }
            ?: document.selectFirst("img[src*='logo'], img[src*='team']")?.attr("src")?.let { fixUrl(it) }
        
        // Extract description/plot
        val description = buildString {
            // Try to get match info
            val matchInfo = document.select(".match-info, .match-details, table")
            if (matchInfo.isNotEmpty()) {
                append("📋 معلومات المباراة:\n")
                matchInfo.select("tr").forEach { row ->
                    val label = row.select("th, .label").text().trim()
                    val value = row.select("td, .value").text().trim()
                    if (label.isNotBlank() && value.isNotBlank()) {
                        append("• $label: $value\n")
                    }
                }
            }
            
            // Add stream servers if available
            val servers = document.select(".servers, .stream-links, .quality-options")
            if (servers.isNotEmpty()) {
                append("\n📡 السيرفرات المتاحة:\n")
                servers.select("a, button").forEachIndexed { index, server ->
                    val serverText = server.text().trim()
                    if (serverText.isNotBlank()) {
                        append("• $serverText\n")
                    }
                }
            }
        }
        
        // Extract stream links from the page
        val streamLinks = mutableListOf<String>()
        
        // Look for iframes
        document.select("iframe[src]").forEach { iframe ->
            val src = iframe.attr("src").trim()
            if (src.isNotBlank()) {
                streamLinks.add(src)
            }
        }
        
        // Look for streaming scripts
        document.select("script").forEach { script ->
            val scriptText = script.html()
            // Look for m3u8 or stream URLs
            Regex("""['"](https?://[^'"]*\.m3u8[^'"]*)['"]""").findAll(scriptText).forEach { match ->
                val url = match.groupValues[1]
                if (url.isNotBlank()) {
                    streamLinks.add(url)
                }
            }
            
            Regex("""['"](https?://[^'"]*stream[^'"]*)['"]""").findAll(scriptText).forEach { match ->
                val url = match.groupValues[1]
                if (url.isNotBlank()) {
                    streamLinks.add(url)
                }
            }
        }
        
        val data = if (streamLinks.isNotEmpty()) {
            streamLinks.joinToString("|||")
        } else {
            url
        }
        
        println("=== Loaded match: $title, found ${streamLinks.size} stream links ===")
        
        return newMovieLoadResponse(title, url, TvType.Movie, data) {
            this.posterUrl = poster
            this.plot = description.ifBlank { "مباراة كرة قدم مباشرة" }
            this.tags = listOf("كرة قدم", "رياضة", "بث مباشر")
        }
    }
    
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var foundLinks = false
        
        println("=== Loading links from data ===")
        
        // Check if data contains multiple stream links
        if (data.contains("|||")) {
            val streamLinks = data.split("|||").filter { it.isNotBlank() }
            
            println("Found ${streamLinks.size} stream links in data")
            
            streamLinks.forEachIndexed { index, streamUrl ->
                println("Processing stream link $index: $streamUrl")
                
                try {
                    loadExtractor(streamUrl, mainUrl, subtitleCallback, callback)
                    foundLinks = true
                    println("Successfully loaded extractor for: $streamUrl")
                } catch (e: Exception) {
                    println("Extractor failed for $streamUrl: ${e.message}")
                    
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
                        println("Added direct link: $streamUrl")
                    }
                }
            }
        } else {
            // Single URL - try to extract from the page
            println("Single URL mode, loading: $data")
            
            try {
                val doc = app.get(data, headers = getHeaders()).document
                
                // Look for iframes
                doc.select("iframe[src]").forEach { iframe ->
                    val src = iframe.attr("src").trim()
                    if (src.isNotBlank()) {
                        println("Found iframe: $src")
                        loadExtractor(src, data, subtitleCallback, callback)
                        foundLinks = true
                    }
                }
                
                // Look for direct video elements
                doc.select("video source[src], video[src]").forEach { video ->
                    val src = video.attr("src").trim()
                    if (src.isNotBlank() && (src.contains(".m3u8") || src.contains(".mp4"))) {
                        println("Found direct video: $src")
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
                
            } catch (e: Exception) {
                println("Error loading page: ${e.message}")
            }
        }
        
        println("=== Finished loading links, found: $foundLinks ===")
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
