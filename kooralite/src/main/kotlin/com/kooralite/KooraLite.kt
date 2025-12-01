package com.kooralite

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLEncoder

class KooraLite : MainAPI() {
    override var mainUrl = "https://www.kooralite.com"
    override var name = "KooraLite - كورة لايت"
    override var lang = "ar"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie)
    
    private fun String.getPosterFromMatch(): String? {
        // Try to extract team logos or match image
        return when {
            this.contains("باريس") || this.contains("psg") -> "https://upload.wikimedia.org/wikipedia/en/a/a7/Paris_Saint-Germain_F.C..svg"
            this.contains("ريال") || this.contains("مدريد") || this.contains("realmadrid") -> "https://upload.wikimedia.org/wikipedia/en/5/56/Real_Madrid_CF.svg"
            this.contains("برشلونة") || this.contains("barcelona") -> "https://upload.wikimedia.org/wikipedia/en/4/47/FC_Barcelona_%28crest%29.svg"
            this.contains("مانشستر") || this.contains("manchester") -> "https://upload.wikimedia.org/wikipedia/en/7/7a/Manchester_United_FC_crest.svg"
            this.contains("ليفربول") || this.contains("liverpool") -> "https://upload.wikimedia.org/wikipedia/en/0/0c/Liverpool_FC.svg"
            this.contains("تشيلسي") || this.contains("chelsea") -> "https://upload.wikimedia.org/wikipedia/en/c/cc/Chelsea_FC.svg"
            this.contains("الأهلي") || this.contains("al-ahly") -> "https://upload.wikimedia.org/wikipedia/ar/7/71/Al_Ahly_SC_logo.png"
            this.contains("الزمالك") || this.contains("zamalek") -> "https://upload.wikimedia.org/wikipedia/ar/4/4b/Zamalek_SC_logo.png"
            else -> null
        }
    }
    
    private fun Element.toSearchResponse(): SearchResponse? {
        // Try different selectors based on actual website structure
        val link = selectFirst("a")?.attr("href") ?: return null
        val href = when {
            link.startsWith("http") -> link
            link.startsWith("/") -> "$mainUrl$link"
            else -> "$mainUrl/$link"
        }
        
        // Extract title from multiple possible locations
        val title = selectFirst("h2, h3, .title, .entry-title, .match-title")?.text()?.trim()
            ?: selectFirst("a")?.attr("title")?.trim()
            ?: selectFirst("img")?.attr("alt")?.trim()
            ?: return null
        
        // Clean up title
        val cleanTitle = title
            .replace("مشاهدة", "")
            .replace("مباراة", "")
            .replace("بث مباشر", "")
            .replace("اون لاين", "")
            .replace("مترجم", "")
            .trim()
        
        // Try to get poster
        val poster = selectFirst("img")?.let { img ->
            img.attr("src").ifBlank { img.attr("data-src") }
        } ?: cleanTitle.getPosterFromMatch()
        
        // Check if it's a live match by URL pattern or text
        val isLive = href.contains("/live/") || href.contains("live") || 
                     title.contains("بث مباشر") || title.contains("مباشر")
        
        val displayTitle = if (isLive) "🔴 $cleanTitle" else cleanTitle
        
        return newMovieSearchResponse(displayTitle, href, TvType.Movie) {
            this.posterUrl = poster
        }
    }
    
    override val mainPage = mainPageOf(
        "$mainUrl/category/matches/" to "المباريات الحية",
        "$mainUrl/category/%d8%af%d9%88%d8%b1%d9%8a-%d8%a7%d8%a8%d8%b7%d8%a7%d9%84-%d8%a3%d9%88%d8%b1%d8%a8%d8%a7/" to "دوري أبطال أوروبا",
        "$mainUrl/category/%d8%a7%d9%84%d8%af%d9%88%d8%b1%d9%8a-%d8%a7%d9%84%d8%a7%d9%86%d8%ac%d9%84%d9%8a%d8%b2%d9%8a/" to "الدوري الإنجليزي",
        "$mainUrl/category/%d8%a7%d9%84%d8%af%d9%88%d8%b1%d9%8a-%d8%a7%d9%84%d8%a7%d8%b3%d8%a8%d8%a7%d9%86%d9%8a/" to "الدوري الإسباني",
        "$mainUrl/category/%d8%a7%d9%84%d8%af%d9%88%d8%b1%d9%8a-%d8%a7%d9%84%d8%a7%d9%8a%d8%b7%d8%a7%d9%84%d9%8a/" to "الدوري الإيطالي",
        "$mainUrl/category/%d8%a7%d9%84%d8%af%d9%88%d8%b1%d9%8a-%d8%a7%d9%84%d8%a7%d9%84%d9%85%d8%a7%d9%86%d9%8a/" to "الدوري الألماني",
        "$mainUrl/category/%d8%a7%d9%84%d8%af%d9%88%d8%b1%d9%8a-%d8%a7%d9%84%d8%b3%d8%b9%d9%88%d8%af%d9%8a/" to "الدوري السعودي",
        "$mainUrl/category/%d8%a7%d9%84%d8%af%d9%88%d8%b1%d9%8a-%d8%a7%d9%84%d9%85%d8%b5%d8%b1%d9%8a/" to "الدوري المصري",
        "$mainUrl/category/%d9%83%d8%a3%d8%b3-%d8%a7%d9%84%d8%b9%d8%a7%d9%84%d9%85/" to "كأس العالم",
        "$mainUrl/category/%d9%83%d8%a3%d8%b3-%d8%a7%d9%84%d8%a7%d9%85%d9%85-%d8%a7%d9%84%d8%a7%d9%81%d8%b1%d9%8a%d9%82%d9%8a%d8%a9/" to "كأس الأمم الأفريقية"
    )
    
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page > 1) "${request.data}page/$page/" else request.data
        val document = app.get(url).document
        
        // Try multiple selectors to find match items
        val selectors = listOf(
            ".post",
            "article",
            ".match-item",
            ".item",
            ".entry",
            ".col-md-4",
            ".col-lg-4",
            ".col-sm-6"
        )
        
        val matches = mutableListOf<SearchResponse>()
        
        for (selector in selectors) {
            if (matches.isNotEmpty()) break
            
            document.select(selector).forEach { element ->
                element.toSearchResponse()?.let { matches.add(it) }
            }
        }
        
        // Fallback: look for any links that might be matches
        if (matches.isEmpty()) {
            document.select("a").forEach { link ->
                val href = link.attr("href")
                val title = link.text().trim()
                
                if (href.contains("/match/") || href.contains("/live/") || 
                    title.contains("مباراة") || title.contains("بث")) {
                    
                    val cleanTitle = title.replace("مشاهدة", "").replace("مباراة", "").trim()
                    if (cleanTitle.isNotBlank()) {
                        val fullUrl = when {
                            href.startsWith("http") -> href
                            href.startsWith("/") -> "$mainUrl$href"
                            else -> "$mainUrl/$href"
                        }
                        
                        matches.add(newMovieSearchResponse(cleanTitle, fullUrl, TvType.Movie))
                    }
                }
            }
        }
        
        return newHomePageResponse(request.name, matches, hasNext = true)
    }
    
    override suspend fun search(query: String): List<SearchResponse> {
        if (query.length < 2) return emptyList()
        
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val searchUrl = "$mainUrl/?s=$encodedQuery"
        
        try {
            val document = app.get(searchUrl).document
            val results = mutableListOf<SearchResponse>()
            
            document.select(".post, article, .search-result").forEach { element ->
                element.toSearchResponse()?.let { results.add(it) }
            }
            
            return results
        } catch (e: Exception) {
            return emptyList()
        }
    }
    
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        
        // Extract title
        val title = document.selectFirst("h1.entry-title, h1.post-title, h1, .title")?.text()?.trim()
            ?: "مباراة كورة لايت"
        
        // Extract description/plot
        val plotBuilder = StringBuilder()
        
        // Try to get match details
        val matchDetails = document.select(".entry-content p, .post-content p, .description p, p")
            .map { it.text().trim() }
            .filter { it.isNotBlank() && !it.contains("function(") }
            .take(3)
        
        if (matchDetails.isNotEmpty()) {
            plotBuilder.append("تفاصيل المباراة:\n")
            matchDetails.forEach { plotBuilder.append("• $it\n") }
        }
        
        // Try to get teams
        val teams = mutableListOf<String>()
        document.select("h2, h3, strong").forEach { element ->
            val text = element.text().trim()
            if (text.contains(" ضد ") || text.contains(" vs ") || text.contains(" VS ")) {
                teams.add(text)
            }
        }
        
        if (teams.isNotEmpty()) {
            plotBuilder.append("\nالفريقان:\n")
            teams.take(2).forEach { plotBuilder.append("⚽ $it\n") }
        }
        
        // Try to get time/date
        val timeElement = document.selectFirst(".match-time, .time, .date, .post-date")
        timeElement?.let {
            plotBuilder.append("\nالوقت: ${it.text().trim()}\n")
        }
        
        // Try to get poster
        val poster = document.selectFirst("img.wp-post-image, img.post-thumbnail, img.attachment-post-thumbnail")?.attr("src")
            ?: document.selectFirst("meta[property='og:image']")?.attr("content")
            ?: title.getPosterFromMatch()
        
        // Look for stream links in the page
        val streamLinks = document.select("iframe[src], embed[src], video source[src]")
            .mapNotNull {
                it.attr("src").ifBlank { it.attr("data-src") }
            }
            .filter { it.isNotBlank() }
            .distinct()
        
        // Also look for links with streaming keywords
        document.select("a[href*='stream'], a[href*='live'], a[href*='watch']").forEach { link ->
            val href = link.attr("href")
            if (href.isNotBlank() && href.startsWith("http")) {
                if (href !in streamLinks) {
                    streamLinks.toMutableList().add(href)
                }
            }
        }
        
        val data = if (streamLinks.isNotEmpty()) {
            streamLinks.joinToString("|||")
        } else {
            url // Fallback to original URL
        }
        
        return newMovieLoadResponse(title, url, TvType.Movie, data) {
            this.posterUrl = poster
            this.plot = plotBuilder.toString().takeIf { it.isNotBlank() }
            
            // Add tags based on content
            val tags = mutableListOf("كرة قدم", "رياضة", "مباراة")
            if (title.contains("بث مباشر") || title.contains("مباشر")) {
                tags.add("بث مباشر")
            }
            if (title.contains("دوري")) {
                tags.add("دوري")
            }
            if (title.contains("كأس")) {
                tags.add("كأس")
            }
            this.tags = tags
            
            // Add some recommendations (other matches on the site)
            val recommendations = document.select(".related-posts a, .widget a").mapNotNull { link ->
                val recTitle = link.text().trim()
                val recHref = link.attr("href")
                
                if (recTitle.isNotBlank() && recHref.isNotBlank() && recTitle.length > 3) {
                    val fullUrl = when {
                        recHref.startsWith("http") -> recHref
                        recHref.startsWith("/") -> "$mainUrl$recHref"
                        else -> "$mainUrl/$recHref"
                    }
                    
                    newMovieSearchResponse(recTitle, fullUrl, TvType.Movie)
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
                    // Try direct extraction
                    tryExtractDirectLinks(streamUrl, callback)
                }
            }
        } else {
            // Single URL - try to extract from the page
            try {
                val doc = app.get(data).document
                
                // Method 1: Look for iframes
                doc.select("iframe[src]").forEach { iframe ->
                    val src = iframe.attr("src")
                    if (src.isNotBlank()) {
                        loadExtractor(src, data, subtitleCallback, callback)
                        foundLinks = true
                    }
                }
                
                // Method 2: Look for video elements
                doc.select("video source[src]").forEach { source ->
                    val videoUrl = source.attr("src")
                    if (videoUrl.isNotBlank()) {
                        val quality = Qualities.Unknown.value
                        
                        callback.invoke(
                            newExtractorLink(
                                name,
                                "$name - بث مباشر",
                                videoUrl,
                                if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            ) {
                                this.referer = data
                                this.quality = quality
                            }
                        )
                        foundLinks = true
                    }
                }
                
                // Method 3: Look for streaming scripts
                doc.select("script").forEach { script ->
                    val scriptText = script.html()
                    
                    // Look for common streaming URLs in scripts
                    val patterns = listOf(
                        Regex("""(https?://[^\s'"]*\.m3u8[^\s'"]*)"""),
                        Regex("""src\s*[:=]\s*['"](https?://[^'"]+)['"]"""),
                        Regex("""file\s*[:=]\s*['"](https?://[^'"]+)['"]"""),
                        Regex("""['"](https?://[^'"]*stream[^'"]*)['"]""")
                    )
                    
                    patterns.forEach { pattern ->
                        pattern.findAll(scriptText).forEach { match ->
                            val url = match.groupValues[1]
                            if (url.isNotBlank() && (url.contains("m3u8") || url.contains("mp4") || url.contains("stream"))) {
                                loadExtractor(url, data, subtitleCallback, callback)
                                foundLinks = true
                            }
                        }
                    }
                }
                
                // Method 4: Look for links with streaming keywords
                doc.select("a").forEach { link ->
                    val href = link.attr("href")
                    val text = link.text().lowercase()
                    
                    if (href.isNotBlank() && href.startsWith("http") &&
                        (text.contains("شاهد") || text.contains("بث") || text.contains("مشاهدة") || 
                         text.contains("stream") || text.contains("watch") || text.contains("live"))) {
                        
                        loadExtractor(href, data, subtitleCallback, callback)
                        foundLinks = true
                    }
                }
                
            } catch (e: Exception) {
                // Error loading page
            }
        }
        
        // Fallback: try to find stream on common football streaming sites
        if (!foundLinks) {
            try {
                // Common football streaming patterns
                val commonStreamPatterns = listOf(
                    "ripple.is",
                    "dubz.co",
                    "streamtape.com",
                    "streamwish.to",
                    "vidhide.com",
                    "vidoza.net"
                )
                
                // Check if URL matches any common pattern
                commonStreamPatterns.forEach { pattern ->
                    if (data.contains(pattern)) {
                        loadExtractor(data, mainUrl, subtitleCallback, callback)
                        foundLinks = true
                        return@forEach
                    }
                }
            } catch (e: Exception) {
                // Fallback failed
            }
        }
        
        return foundLinks
    }
    
    private suspend fun tryExtractDirectLinks(url: String, callback: (ExtractorLink) -> Unit) {
        try {
            val response = app.get(url)
            val contentType = response.headers["content-type"] ?: ""
            
            if (contentType.contains("video") || contentType.contains("m3u8") || 
                contentType.contains("mp4") || url.contains(".m3u8") || url.contains(".mp4")) {
                
                callback.invoke(
                    newExtractorLink(
                        name,
                        "$name - بث مباشر",
                        url,
                        if (url.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = mainUrl
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
        } catch (e: Exception) {
            // Ignore errors
        }
    }
}