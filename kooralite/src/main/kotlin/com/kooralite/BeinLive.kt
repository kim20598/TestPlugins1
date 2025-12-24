package com.kooralite

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLEncoder

class BeinLive : MainAPI() {

    override var mainUrl = "https://www.bein-live.com"
    override var name = "Bein Live - بين لايف"
    override var lang = "ar"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie)

    private val customPosterUrl = "https://raw.githubusercontent.com/kim20598/TestPlugins1/master/beinlive/poster.png"

    // ========================= Utils =========================

    private fun fixUrl(url: String): String = when {
        url.isBlank() -> ""
        url.startsWith("http") -> url
        url.startsWith("//") -> "https:$url"
        url.startsWith("/") -> "$mainUrl$url"
        else -> "$mainUrl/$url"
    }

    private fun decodeBase64(encoded: String): String =
        runCatching {
            String(Base64.decode(encoded, Base64.DEFAULT))
        }.getOrElse { encoded }

    // ========================= Search Item =========================

    private fun Element.toMatchSearchResponse(): SearchResponse? {
        // Match items from main page (gr-item)
        if (hasClass("gr-item")) {
            val link = selectFirst("a.gr-inner")?.attr("href") ?: return null
            val href = fixUrl(link)
            
            val title = selectFirst(".gr-title, .gr-info h3")?.text()?.trim() ?: return null
            
            val thumbnail = selectFirst(".TmFlag")?.attr("data-src") ?: ""
            val poster = if (thumbnail.isNotBlank()) fixUrl(thumbnail) else customPosterUrl
            
            return newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
            }
        }
        
        // Match items from match listings (AY_Match)
        if (hasClass("AY_Match")) {
            val link = selectFirst("a")?.attr("href") ?: return null
            val href = fixUrl(link)
            
            val team1 = selectFirst(".MT_Team.TM1 .TM_Name")?.text()?.trim() ?: return null
            val team2 = selectFirst(".MT_Team.TM2 .TM_Name")?.text()?.trim() ?: return null
            
            val time = selectFirst(".MT_Time")?.text()?.trim().orEmpty()
            val tournament = selectFirst(".MT_Info li:last-child span")?.text()?.trim().orEmpty()
            val broadcast = selectFirst(".MT_Info li:first-child span")?.text()?.trim().orEmpty()
            
            val statusClass = classNames().firstOrNull {
                it in listOf("live", "finished", "comming-soon", "not-started")
            }.orEmpty()
            
            val statusText = when (statusClass) {
                "live" -> "🔴 مباشر"
                "finished" -> "✅ انتهت"
                "comming-soon" -> "⏳ قادم"
                "not-started" -> "⏳ لم تبدأ"
                else -> "⏳ قادم"
            }
            
            val title = "$statusText $team1 vs $team2" +
                    if (time.isNotBlank()) " ($time)" else ""
            
            return newMovieSearchResponse(title, href, TvType.Movie) {
                posterUrl = customPosterUrl
            }
        }
        
        return null
    }

    // ========================= Main Page =========================

    override val mainPage = mainPageOf(
        "$mainUrl/" to "الرئيسية",
        "$mainUrl/matches-today_1/" to "مباريات اليوم",
        "$mainUrl/matches-yesterday/" to "مباريات الأمس",
        "$mainUrl/matches-tomorrow/" to "مباريات الغد"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page > 1) "${request.data}page/$page/" else request.data
        val document = app.get(url).document
        
        val items = mutableListOf<SearchResponse>()
        
        // Handle different page structures
        when {
            url.contains("matches-") -> {
                // Match listing pages
                items.addAll(document.select(".AY_Match").mapNotNull { it.toMatchSearchResponse() })
            }
            else -> {
                // Home page with gr-item
                items.addAll(document.select(".gr-item").mapNotNull { it.toMatchSearchResponse() })
            }
        }
        
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    // ========================= Search =========================

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.length < 2) return emptyList()
        
        val searchUrl = "$mainUrl/?s=${URLEncoder.encode(query, "UTF-8")}"
        val document = app.get(searchUrl).document
        
        return document.select(".gr-item, .AY_Match, article.post").mapNotNull { element ->
            element.toMatchSearchResponse()
        }
    }

    // ========================= Load =========================

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        
        // Extract title
        val title = document.selectFirst("h1.EntryTitle, .EntryTitle")?.text()?.trim()
            ?: document.selectFirst("title")?.text()?.substringBefore("|")?.trim()
            ?: "مباراة مباشرة"
        
        // Extract teams from match header
        val team1 = document.selectFirst(".McTeam:first-child .TmNeam")?.text()?.trim() ?: ""
        val team2 = document.selectFirst(".McTeam:last-child .TmNeam")?.text()?.trim() ?: ""
        
        val matchTitle = if (team1.isNotBlank() && team2.isNotBlank()) {
            "$team1 vs $team2"
        } else {
            title
        }
        
        // Extract match info
        val matchInfo = mutableMapOf<String, String>()
        document.select(".AY-MatchInfo tr").forEach { row ->
            val key = row.selectFirst("th")?.text()?.trim()?.removeSuffix(":")
            val value = row.selectFirst("td")?.text()?.trim()
            if (key != null && value != null) {
                matchInfo[key] = value
            }
        }
        
        // Build description
        val description = buildString {
            if (team1.isNotBlank() && team2.isNotBlank()) {
                appendLine("⚽ $team1 vs $team2")
                appendLine()
            }
            
            matchInfo.forEach { (key, value) ->
                when (key) {
                    "البطولة" -> appendLine("🏆 $value")
                    "تاريخ المباراة" -> appendLine("📅 $value")
                    "توقيت المباراة" -> appendLine("🕒 $value")
                    "اسم القناة" -> appendLine("📺 $value")
                    "نتيجة المباراة" -> appendLine("🏁 $value")
                    else -> appendLine("$key: $value")
                }
            }
            
            // Add some content
            document.select(".entry p").take(3).forEach { p ->
                val text = p.text().trim()
                if (text.isNotBlank()) {
                    appendLine()
                    appendLine(text)
                }
            }
        }
        
        // Extract poster image
        val poster = document.selectFirst("meta[property='og:image']")?.attr("content")
            ?: document.selectFirst(".fut-img img")?.attr("src")
            ?: customPosterUrl
        
        return newMovieLoadResponse(matchTitle, url, TvType.Movie, url) {
            this.posterUrl = fixUrl(poster)
            this.plot = description.trim()
            this.year = 2025 // Current year based on the HTML
        }
    }

    // ========================= Load Links =========================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        val visited = mutableSetOf<String>()
        var foundLinks = false
        
        // Strategy 1: Direct video-serv links (from your HTML)
        document.select(".video-serv a[href]").forEach { link ->
            val href = fixUrl(link.attr("href"))
            if (href.isNotBlank() && visited.add(href)) {
                foundLinks = extractStreamFromIframe(href, callback, visited) || foundLinks
            }
        }
        
        // Strategy 2: Iframe in server-body
        document.select(".server-body iframe[src]").forEach { iframe ->
            val src = fixUrl(iframe.attr("src"))
            if (src.isNotBlank() && visited.add(src)) {
                foundLinks = extractStreamFromIframe(src, callback, visited) || foundLinks
            }
        }
        
        // Strategy 3: Direct iframe on page
        document.select("iframe[src]").forEach { iframe ->
            val src = fixUrl(iframe.attr("src"))
            if (src.isNotBlank() && visited.add(src)) {
                foundLinks = extractStreamFromIframe(src, callback, visited) || foundLinks
            }
        }
        
        // Strategy 4: Extract from albaplayer iframe
        document.select("#yalla-ajax-server iframe[src]").forEach { iframe ->
            val src = fixUrl(iframe.attr("src"))
            if (src.contains("yallashoooootlive.com") && visited.add(src)) {
                foundLinks = extractFromAlbaPlayer(src, callback) || foundLinks
            }
        }
        
        return foundLinks
    }
    
    private suspend fun extractStreamFromIframe(
        url: String,
        callback: (ExtractorLink) -> Unit,
        visited: MutableSet<String>
    ): Boolean {
        val doc = app.get(url).document
        var found = false
        
        // Look for m3u8 links
        doc.select("script").forEach { script ->
            val scriptText = script.html()
            
            // Pattern 1: Direct m3u8 URLs
            val m3u8Pattern = Regex("""(https?://[^\s"']+\.m3u8[^\s"']*)""")
            m3u8Pattern.findAll(scriptText).forEach { match ->
                val streamUrl = match.value
                if (streamUrl.contains("m3u8") && visited.add(streamUrl)) {
                    callback(
                        newExtractorLink(
                            name,
                            "M3U8 Stream",
                            streamUrl,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = url
                            this.quality = Qualities.Unknown.value
                        }
                    )
                    found = true
                }
            }
            
            // Pattern 2: Base64 encoded URLs (common in sports streams)
            val base64Pattern = Regex("""["']([A-Za-z0-9+/=]+)["']""")
            base64Pattern.findAll(scriptText).forEach { match ->
                runCatching {
                    val decoded = decodeBase64(match.value)
                    if (decoded.contains(".m3u8") && decoded.startsWith("http")) {
                        if (visited.add(decoded)) {
                            callback(
                                newExtractorLink(
                                    name,
                                    "Base64 Stream",
                                    decoded,
                                    type = ExtractorLinkType.M3U8
                                ) {
                                    this.referer = url
                                    this.quality = Qualities.Unknown.value
                                }
                            )
                            found = true
                        }
                    }
                }
            }
            
            // Pattern 3: Player sources
            val playerPatterns = listOf(
                """player\.(?:src|setSrc|load)\(['"]([^'"]+)['"]\)""",
                """source.*?['"](https?://[^'"]+)['"]""",
                """file.*?:.*?['"](https?://[^'"]+)['"]""",
                """hlsManifestUrl.*?:.*?['"](https?://[^'"]+)['"]"""
            )
            
            playerPatterns.forEach { pattern ->
                Regex(pattern).findAll(scriptText).forEach { match ->
                    val streamUrl = match.groupValues[1]
                    if (streamUrl.contains("m3u8") && visited.add(streamUrl)) {
                        callback(
                            newExtractorLink(
                                name,
                                "Player Stream",
                                streamUrl,
                                type = ExtractorLinkType.M3U8
                            ) {
                                this.referer = url
                                this.quality = Qualities.Unknown.value
                            }
                        )
                        found = true
                    }
                }
            }
        }
        
        // Check for iframes within iframes
        doc.select("iframe[src]").forEach { iframe ->
            val src = fixUrl(iframe.attr("src"))
            if (src.isNotBlank() && visited.add(src)) {
                found = extractStreamFromIframe(src, callback, visited) || found
            }
        }
        
        return found
    }
    
    private suspend fun extractFromAlbaPlayer(
        url: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val html = app.get(url).text
        var found = false
        
        // Look for Clappr player configuration
        val clapprPattern = Regex("""["'](https?://[^"']+\.m3u8)["']""")
        clapprPattern.findAll(html).forEach { match ->
            val streamUrl = match.value
            if (streamUrl.contains("m3u8")) {
                callback(
                    newExtractorLink(
                        name,
                        "Clappr Player",
                        streamUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = url
                        this.quality = Qualities.Unknown.value
                    }
                )
                found = true
            }
        }
        
        // Look for video source tags
        val videoPattern = Regex("""<source[^>]+src=["']([^"']+)["'][^>]*>""", RegexOption.IGNORE_CASE)
        videoPattern.findAll(html).forEach { match ->
            val src = match.groupValues[1]
            if (src.contains("m3u8") || src.contains("mp4")) {
                val fullUrl = if (src.startsWith("http")) src else "https:$src"
                callback(
                    newExtractorLink(
                        name,
                        "Video Source",
                        fullUrl,
                        type = if (src.contains("m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = url
                        this.quality = Qualities.Unknown.value
                    }
                )
                found = true
            }
        }
        
        return found
    }
}
