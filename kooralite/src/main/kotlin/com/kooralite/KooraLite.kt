package com.kooralite

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLEncoder

class KooraLite : MainAPI() {
    override var mainUrl = "https://www.fullmatch-hd.com"
    override var name = "KooraLite - كورة لايت"
    override var lang = "ar"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie)

    private fun decodeBase64(encoded: String): String {
        return try {
            String(android.util.Base64.decode(encoded, android.util.Base64.DEFAULT))
        } catch (e: Exception) {
            encoded
        }
    }

    private fun Element.toMatchSearchResponse(): SearchResponse? {
        // Extract match information from .AY_Match div
        val link = selectFirst("a")?.attr("href") ?: return null
        val href = fixUrl(link)

        // Get team names (use consistent class name MT_Team)
        val team1 = selectFirst(".MT_Team.TM1 .TM_Name")?.text()?.trim() ?: ""
        val team2 = selectFirst(".MT_Team.TM2 .TM_Name")?.text()?.trim() ?: ""

        if (team1.isBlank() || team2.isBlank()) return null

        // Create title: Team1 vs Team2
        val title = "$team1 vs $team2"

        // Get match status: check class names and also fallback to status text
        val statusClass = classNames().firstOrNull { it in listOf("live", "finished", "coming-soon") } ?: ""
        val statusText = when (statusClass) {
            "live" -> "🔴 مباشر"
            "finished" -> "✅ انتهت"
            "coming-soon" -> "⏳ قادم"
            else -> {
                // fallback: try to read textual status from a dedicated element if present
                val txt = selectFirst(".MT_Status")?.text()?.trim()
                when {
                    txt?.contains("مباشر") == true -> "🔴 مباشر"
                    txt?.contains("انتهت") == true -> "✅ انتهت"
                    txt != null && txt.isNotBlank() -> "⏳ $txt"
                    else -> "⏳ قادم"
                }
            }
        }

        // Get match time
        val time = selectFirst(".MT_Time")?.text()?.trim() ?: ""

        // Get tournament/league - use safer selection
        val tournament = selectFirst(".MT_Info li:last-child span")?.text()?.trim()
            ?: selectFirst(".MT_Info li")?.let { select(".MT_Info li").lastOrNull()?.selectFirst("span")?.text()?.trim() }
            ?: ""

        // Get both team logos (use consistent MT_Team)
        val team1LogoRaw = selectFirst(".MT_Team.TM1 .TM_Logo img")?.attr("src")
        val team2LogoRaw = selectFirst(".MT_Team.TM2 .TM_Logo img")?.attr("src")

        val team1Logo = team1LogoRaw?.let { if (it.startsWith("http")) it else fixUrl(it) }
        val team2Logo = team2LogoRaw?.let { if (it.startsWith("http")) it else fixUrl(it) }

        // Choose the best poster: Team 1 logo, then Team 2 logo
        val poster = team1Logo ?: team2Logo

        // Create enhanced title with time if available
        val enhancedTitle = if (time.isNotBlank()) {
            "$statusText $title ($time)"
        } else {
            "$statusText $title"
        }

        // Store match data - include both logos
        // Data format: href|title|time|tournament|statusClass|poster|team1|team2|team1Logo|team2Logo
        val matchData = listOf(title, time, tournament, statusClass, poster ?: "", team1, team2, team1Logo ?: "", team2Logo ?: "")
            .joinToString("|")
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
        var tournament = parts.getOrNull(3) ?: ""
        val status = parts.getOrNull(4) ?: ""
        val poster = parts.getOrNull(5) // Main poster (team1 logo)
        val team1 = parts.getOrNull(6) ?: ""
        val team2 = parts.getOrNull(7) ?: ""
        val team1Logo = parts.getOrNull(8)
        val team2Logo = parts.getOrNull(9)

        val document = app.get(actualUrl).document

        // Build description
        val description = buildString {
            if (team1.isNotBlank() && team2.isNotBlank()) {
                append("⚽ $team1 vs $team2\n")

                // Show both team logos if available
                if (team1Logo != null || team2Logo != null) {
                    append("\n🏁 فرق المباراة:\n")
                    if (team1Logo != null) {
                        append("• $team1\n")
                    }
                    if (team2Logo != null) {
                        append("• $team2\n")
                    }
                }
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
                append("\n📋 بطاقة المباراة:\n")

                // Extract table rows
                matchTable.select("tr").forEach { row ->
                    val header = row.select("th").text().trim()
                    val value = row.select("td").text().trim()

                    if (header.isNotBlank() && value.isNotBlank()) {
                        when (header) {
                            "البطولة" -> append("🏆 $header: $value\n")
                            "اسم القناة" -> append("📺 $header: $value\n")
                            "تاريخ المباراة" -> append("📅 $header: $value\n")
                            "توقيت المباراة" -> append("⏰ $header: $value\n")
                            "المعلق" -> append("🎙️ $header: $value\n")
                            "نتيجة المباراة" -> append("📊 $header: $value\n")
                            else -> append("• $header: $value\n")
                        }
                    }
                }
            }

            // Try to find stream quality/links info
            document.select(".video-serv a").forEach { streamLink ->
                val streamName = streamLink.text().trim()
                if (streamName.isNotBlank()) {
                    append("📡 $streamName\n")
                }
            }

            // Add server information if available
            val servers = document.select(".video-serv a")
            if (servers.isNotEmpty()) {
                append("\n🔗 السيرفرات المتاحة: ${servers.size}\n")
            }
        }

        // Store only the actual URL - we'll extract the stream in loadLinks
        val data = actualUrl

        return newMovieLoadResponse(title, url, TvType.Movie, data) {
            this.posterUrl = poster
            this.plot = description.trim()
            
            val tags = mutableListOf("كرة قدم", "رياضة", "مباراة")
            if (status == "live") tags.add("بث مباشر")
            if (team1.isNotBlank()) tags.add(team1)
            if (team2.isNotBlank()) tags.add(team2)
            this.tags = tags
        }
    }

    private suspend fun extractAlbaPlayerStream(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val doc = app.get(url, referer = referer).document
            
            // 1. Look for AlbaPlayerControl script - This is what WORKS!
            doc.select("script:contains(AlbaPlayerControl)").forEach { script ->
                val scriptText = script.html()
                
                // Extract base64 encoded string from: AlbaPlayerControl('BASE64','hls')
                val regex = Regex("""AlbaPlayerControl\('([A-Za-z0-9+/=]+)','hls'\)""")
                val match = regex.find(scriptText)
                
                if (match != null) {
                    val base64String = match.groupValues[1]
                    val decodedUrl = decodeBase64(base64String)
                    
                    if (decodedUrl.isNotBlank()) {
                        val streamUrl = if (decodedUrl.startsWith("http")) decodedUrl else "https://$decodedUrl"
                        
                        callback.invoke(
                            newExtractorLink(
                                name,
                                "$name - بث مباشر",
                                streamUrl,
                                ExtractorLinkType.M3U8
                            ) {
                                this.referer = url
                                this.quality = Qualities.Unknown.value
                            }
                        )
                        return true
                    }
                }
            }
            
            // 2. Look for direct M3U8 links in scripts
            doc.select("script").forEach { script ->
                val scriptText = script.html()
                // Simple M3U8 pattern
                val m3u8Pattern = Regex("""['"](https?://[^'"]*\.m3u8[^'"]*)['"]""")
                m3u8Pattern.findAll(scriptText).forEach { match ->
                    val streamUrl = match.groupValues[1]
                    if (streamUrl.isNotBlank() && streamUrl.contains("m3u8")) {
                        callback.invoke(
                            newExtractorLink(
                                name,
                                "$name - بث مباشر",
                                streamUrl,
                                ExtractorLinkType.M3U8
                            ) {
                                this.referer = url
                                this.quality = Qualities.Unknown.value
                            }
                        )
                        return true
                    }
                }
            }
            
        } catch (e: Exception) {
            // If extraction fails, just return false - don't try other methods
            return false
        }
        return false
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val dataUrl = fixUrl(data)
        
        // 1. Try albaplayer extraction FIRST (this is what WORKS!)
        if (dataUrl.contains("albaplayer") || dataUrl.contains("sia.watch")) {
            if (extractAlbaPlayerStream(dataUrl, mainUrl, subtitleCallback, callback)) {
                return true
            }
        }
        
        // 2. Try direct extraction for other URLs
        try {
            loadExtractor(dataUrl, mainUrl, subtitleCallback, callback)
            return true
        } catch (e: Exception) {
            // If direct extraction fails, return false immediately
            return false
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
