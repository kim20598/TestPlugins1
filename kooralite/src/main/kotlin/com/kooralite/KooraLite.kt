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

        // Fallback: get any links that look like matches
        if (items.isEmpty()) {
            document.select("a").forEach { link ->
                val href = link.attr("href")
                val text = link.text().trim()

                if (href.contains("/match/") || href.contains("stream-in.live") ||
                    text.contains("مباراة") || text.contains("بث مباشر")
                ) {
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

        // Look for stream links
        val rawStreamLinks = mutableSetOf<String>()

        // Method 1: Look for iframes in the page
        document.select("iframe[src]").forEach { iframe ->
            val srcRaw = iframe.attr("src")
            val src = fixUrl(srcRaw)
            if (src.isNotBlank()) {
                // Check for YouTube iframes specifically
                if (src.contains("youtube.com") || src.contains("youtu.be")) {
                    rawStreamLinks.add(src)
                } else if (src.contains("stream")) {
                    rawStreamLinks.add(src)
                } else {
                    rawStreamLinks.add(src)
                }
            }
        }

        // Method 2: Check if URL is already a stream link
        if (actualUrl.contains("stream-in.live") || actualUrl.contains("stream") ||
            actualUrl.contains("albaplayer") || actualUrl.contains("max.mpnh.online") ||
            actualUrl.contains("sia.watch")
        ) {
            rawStreamLinks.add(actualUrl)
        }

        // Method 3: Look for video elements
        document.select("video source[src]").forEach { source ->
            val srcRaw = source.attr("src")
            val src = fixUrl(srcRaw)
            if (src.isNotBlank()) {
                rawStreamLinks.add(src)
            }
        }

        // Method 4: Look for links with streaming keywords
        document.select("a[href*='stream'], a[href*='watch'], a[href*='live']").forEach { link ->
            val hrefRaw = link.attr("href")
            val href = fixUrl(hrefRaw)
            if (href.isNotBlank()) {
                rawStreamLinks.add(href)
            }
        }

        // Method 5: Look for YouTube embed URLs (and other URLs) in scripts
        document.select("script").forEach { script ->
            val scriptText = script.html()
            val patterns = listOf(
                Regex("""youtube\.com/embed/([^"'\s?&>]+)"""),
                Regex("""youtu\.be/([^"'\s?&>]+)"""),
                Regex("""['"](https?://[^"']*\.m3u8[^"']*)['"]"""),
                Regex("""['"](https?://[^"']*\.mp4[^"']*)['"]"""),
                Regex("""(https?://[^\s'"]*\.m3u8[^\s'"]*)"""),
                Regex("""(https?://[^\s'"]*\.mp4[^\s'"]*)""")
            )

            patterns.forEach { pattern ->
                pattern.findAll(scriptText).forEach { match ->
                    val g = match.groupValues.getOrNull(1) ?: ""
                    if (g.isNotBlank()) {
                        if (!g.startsWith("http") && (pattern.pattern.contains("youtube") || pattern.pattern.contains("youtu.be"))) {
                            rawStreamLinks.add("https://www.youtube.com/watch?v=$g")
                        } else {
                            val fixed = if (g.startsWith("http")) g else fixUrl(g)
                            rawStreamLinks.add(fixed)
                        }
                    }
                }
            }
        }

        // Normalize/clean links and join
        val streamLinks = rawStreamLinks.map { it.trim() }.filter { it.isNotBlank() }.map { fixUrl(it) }.toSet()

        val data = if (streamLinks.isNotEmpty()) {
            streamLinks.joinToString("|||")
        } else {
            actualUrl
        }

        return newMovieLoadResponse(title, url, TvType.Movie, data) {
            // Use team1 logo as main poster
            this.posterUrl = poster

            this.plot = description.trim()

            // Add tags - extract from table if available
            val tags = mutableListOf("كرة قدم", "رياضة", "مباراة")

            // Try to extract tournament from table
            val matchTable = document.select("table.table-bordered")
            var extractedTournament = tournament

            if (matchTable.isNotEmpty()) {
                matchTable.select("tr").forEach { row ->
                    val header = row.select("th").text().trim()
                    val value = row.select("td").text().trim()

                    if (header == "البطولة" && value.isNotBlank()) {
                        extractedTournament = value
                        // Split tournament names by comma and add each as tag
                        value.split(",").map { it.trim() }.filter { it.isNotBlank() }.forEach { tag ->
                            if (!tags.contains(tag)) {
                                tags.add(tag)
                            }
                        }
                    }

                    // Add channel as tag
                    if (header == "اسم القناة" && value.isNotBlank() && value != "غير معروف") {
                        if (!tags.contains(value)) {
                            tags.add(value)
                        }
                    }
                }
            } else if (tournament.isNotBlank()) {
                tags.add(tournament)
            }

            if (status == "live") {
                tags.add("بث مباشر")
            }

            // Add team names as tags
            if (team1.isNotBlank()) {
                tags.add(team1)
            }
            if (team2.isNotBlank()) {
                tags.add(team2)
            }

            this.tags = tags

            // Add recommendations
            val recommendations = document.select(".related-posts a, .widget a, .gr-item a").mapNotNull { link ->
                val recTitle = link.text().trim()
                val recHref = link.attr("href")

                if (recTitle.isNotBlank() && recHref.isNotBlank() && recTitle.length > 3) {
                    val fullUrl = fixUrl(recHref)
                    newMovieSearchResponse(recTitle, fullUrl, TvType.Movie)
                } else null
            }.take(5)

            this.recommendations = recommendations
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
            
            // Look for the AlbaPlayerControl script - FIXED REGEX
            doc.select("script:contains(AlbaPlayerControl)").forEach { script ->
                val scriptText = script.html()
                
                // Extract the base64 encoded string - Match: AlbaPlayerControl('BASE64','hls')
                val regex = Regex("""AlbaPlayerControl\('([A-Za-z0-9+/=]+)','hls'\)""")
                val match = regex.find(scriptText)
                
                if (match != null) {
                    val base64String = match.groupValues[1]
                    val decodedUrl = decodeBase64(base64String)
                    
                    if (decodedUrl.isNotBlank()) {
                        // The decoded URL is the M3U8 stream
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
            
            // Also check for direct M3U8 links in other scripts
            doc.select("script").forEach { script ->
                val scriptText = script.html()
                // Look for M3U8 URLs that might be directly embedded
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
            
            // Also look for video source directly
            doc.select("video source[src]").forEach { source ->
                val videoUrl = fixUrl(source.attr("src"))
                if (videoUrl.isNotBlank()) {
                    callback.invoke(
                        newExtractorLink(
                            name,
                            "$name - بث مباشر",
                            videoUrl,
                            if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            this.referer = url
                            this.quality = Qualities.Unknown.value
                        }
                    )
                    return true
                }
            }
            
            // Look for HLS.js initialization
            doc.select("script").forEach { script ->
                val scriptText = script.html()
                val hlsPatterns = listOf(
                    Regex("""src\s*:\s*['"](https?://[^'"]+\.m3u8)['"]"""),
                    Regex("""hls\.loadSource\s*\(\s*['"]([^'"]+)['"]"""),
                    Regex("""loadSource\s*\(\s*['"](https?://[^'"]+\.m3u8)['"]""")
                )
                
                hlsPatterns.forEach { pattern ->
                    pattern.findAll(scriptText).forEach { match ->
                        val streamUrl = match.groupValues[1]
                        if (streamUrl.isNotBlank()) {
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
            }
            
        } catch (e: Exception) {
            // If extraction fails, try to load the URL directly
            try {
                loadExtractor(url, referer, subtitleCallback, callback)
                return true
            } catch (e: Exception) {
                // Ignore
            }
        }
        return false
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
            val streamLinks = data.split("|||").map { it.trim() }.filter { it.isNotBlank() }.map { fixUrl(it) }

            streamLinks.forEach { streamUrl ->
                try {
                    // Check for albaplayer URLs first
                    if (streamUrl.contains("albaplayer") || streamUrl.contains("sia.watch")) {
                        foundLinks = extractAlbaPlayerStream(streamUrl, mainUrl, subtitleCallback, callback) || foundLinks
                    }
                    // Check if it's a YouTube URL
                    else if (isYouTubeUrl(streamUrl)) {
                        foundLinks = extractYouTubeStream(streamUrl, subtitleCallback, callback) || foundLinks
                    } else {
                        loadExtractor(streamUrl, mainUrl, subtitleCallback, callback)
                        foundLinks = true
                    }
                } catch (e: Exception) {
                    // Try direct extraction
                    tryExtractDirectLink(streamUrl, callback)
                }
            }
        } else {
            // Single URL - try to extract from the page
            val dataUrl = fixUrl(data)
            
            // Check for albaplayer URLs first
            if (dataUrl.contains("albaplayer") || dataUrl.contains("sia.watch")) {
                foundLinks = extractAlbaPlayerStream(dataUrl, mainUrl, subtitleCallback, callback)
                if (foundLinks) return true
            }
            
            try {
                val doc = app.get(dataUrl).document

                // First look for alkoora.live iframes (the main stream iframe)
                doc.select("iframe[src*='alkoora.live'], iframe[src*='stream-in.live'], iframe[src*='albaplayer']").forEach { iframe ->
                    val src = fixUrl(iframe.attr("src"))
                    if (src.isNotBlank()) {
                        // Extract from the iframe
                        foundLinks = extractStreamFromIframe(src, dataUrl, subtitleCallback, callback) || foundLinks
                    }
                }

                // Look for YouTube iframes
                doc.select("iframe[src*='youtube.com'], iframe[src*='youtu.be']").forEach { iframe ->
                    val src = fixUrl(iframe.attr("src"))
                    if (src.isNotBlank()) {
                        foundLinks = extractYouTubeStream(src, subtitleCallback, callback) || foundLinks
                    }
                }

                // Look for other iframes
                doc.select("iframe[src]").forEach { iframe ->
                    val src = fixUrl(iframe.attr("src"))
                    if (src.isNotBlank() && !src.contains("alkoora.live") && !src.contains("stream-in.live") &&
                        !src.contains("youtube.com") && !src.contains("youtu.be") && !src.contains("albaplayer")
                    ) {
                        loadExtractor(src, dataUrl, subtitleCallback, callback)
                        foundLinks = true
                    }
                }

                // Look for direct video links
                doc.select("video source[src]").forEach { source ->
                    val videoUrl = fixUrl(source.attr("src"))
                    if (videoUrl.isNotBlank()) {
                        callback.invoke(
                            newExtractorLink(
                                name,
                                "$name - بث مباشر",
                                videoUrl,
                                if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            ) {
                                this.referer = dataUrl
                                this.quality = Qualities.Unknown.value
                            }
                        )
                        foundLinks = true
                    }
                }

                // Look for streaming scripts
                doc.select("script").forEach { script ->
                    val scriptText = script.html()

                    // Common streaming URL patterns
                    val patterns = listOf(
                        Regex("""(https?://[^\s'"]*\.m3u8[^\s'"]*)"""),
                        Regex("""['"](https?://[^'"]*stream[^'"]*)['"]"""),
                        Regex("""src\s*[:=]\s*['"](https?://[^'"]+)['"]"""),
                        // YouTube patterns
                        Regex("""youtube\.com/embed/([^"']+)"""),
                        Regex("""youtu\.be/([^"']+)"""),
                        // AlbaPlayerControl pattern
                        Regex("""AlbaPlayerControl\('([A-Za-z0-9+/=]+)','hls'\)""")
                    )

                    patterns.forEach { pattern ->
                        pattern.findAll(scriptText).forEach { match ->
                            val found = match.groupValues.getOrNull(1) ?: ""
                            if (found.isNotBlank()) {
                                if (pattern.pattern.contains("youtube")) {
                                    // It's a YouTube ID, construct full URL
                                    val youtubeUrl = if (found.startsWith("http")) found else "https://www.youtube.com/watch?v=$found"
                                    foundLinks = extractYouTubeStream(youtubeUrl, subtitleCallback, callback) || foundLinks
                                } else if (pattern.pattern.contains("AlbaPlayerControl")) {
                                    // It's a base64 encoded stream URL
                                    val decodedUrl = decodeBase64(found)
                                    if (decodedUrl.isNotBlank()) {
                                        val streamUrl = if (decodedUrl.startsWith("http")) decodedUrl else "https://$decodedUrl"
                                        callback.invoke(
                                            newExtractorLink(
                                                name,
                                                "$name - بث مباشر",
                                                streamUrl,
                                                ExtractorLinkType.M3U8
                                            ) {
                                                this.referer = dataUrl
                                                this.quality = Qualities.Unknown.value
                                            }
                                        )
                                        foundLinks = true
                                    }
                                } else {
                                    val fixed = if (found.startsWith("http")) found else fixUrl(found)
                                    if (fixed.contains(".m3u8") || fixed.contains("stream") || fixed.contains(".mp4")) {
                                        loadExtractor(fixed, dataUrl, subtitleCallback, callback)
                                        foundLinks = true
                                    }
                                }
                            }
                        }
                    }
                }

            } catch (e: Exception) {
                // Error loading page - try direct URL
                if (isYouTubeUrl(dataUrl)) {
                    foundLinks = extractYouTubeStream(dataUrl, subtitleCallback, callback) || foundLinks
                } else {
                    try {
                        loadExtractor(dataUrl, mainUrl, subtitleCallback, callback)
                        foundLinks = true
                    } catch (e: Exception) {
                        // Ignore
                    }
                }
            }
        }

        // If still no links found, check if URL is from stream-in.live or albaplayer
        if (!foundLinks && (data.contains("stream-in.live") || data.contains("/2025/") ||
                    data.contains("albaplayer") || data.contains("max.mpnh.online") ||
                    data.contains("sia.watch"))
        ) {
            try {
                // Try albaplayer extraction first
                foundLinks = extractAlbaPlayerStream(fixUrl(data), mainUrl, subtitleCallback, callback)
                if (!foundLinks) {
                    loadExtractor(fixUrl(data), mainUrl, subtitleCallback, callback)
                    foundLinks = true
                }
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
        val srcFixed = fixUrl(iframeSrc)

        try {
            val iframeDoc = app.get(srcFixed, referer = referer).document

            // Check for albaplayer in iframe
            if (srcFixed.contains("albaplayer") || srcFixed.contains("sia.watch")) {
                foundLinks = extractAlbaPlayerStream(srcFixed, referer, subtitleCallback, callback)
                if (foundLinks) return true
            }

            // Method 1: Look for video elements in iframe
            iframeDoc.select("video source[src]").forEach { source ->
                val videoUrl = fixUrl(source.attr("src"))
                if (videoUrl.isNotBlank()) {
                    callback.invoke(
                        newExtractorLink(
                            name,
                            "$name - بث مباشر",
                            videoUrl,
                            if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            this.referer = srcFixed
                            this.quality = Qualities.Unknown.value
                        }
                    )
                    foundLinks = true
                }
            }

            // Method 2: Look for YouTube iframes in iframe
            iframeDoc.select("iframe[src*='youtube.com'], iframe[src*='youtu.be']").forEach { iframe ->
                val src = fixUrl(iframe.attr("src"))
                if (src.isNotBlank()) {
                    foundLinks = extractYouTubeStream(src, subtitleCallback, callback) || foundLinks
                }
            }

            // Method 3: Look for streaming scripts in iframe
            val iframeScripts = iframeDoc.select("script").html()

            // Check for common streaming URL patterns in iframe
            val patterns = listOf(
                Regex("""(https?://[^\s'"]*\.m3u8[^\s'"]*)"""),
                Regex("""['"](https?://[^'"]*stream[^'"]*)['"]"""),
                Regex("""['"](https?://[^'"]*\.mp4[^'"]*)['"]"""),
                Regex("""src\s*[:=]\s*['"](https?://[^'"]+)['"]"""),
                Regex("""file\s*[:=]\s*['"](https?://[^'"]+\.m3u8)['"]"""),
                Regex("""hls\.src\s*=\s*['"]([^'"]+)['"]"""),
                Regex("""player\.src\s*=\s*['"]([^'"]+)['"]"""),
                // YouTube patterns
                Regex("""youtube\.com/embed/([^"']+)"""),
                Regex("""youtu\.be/([^"']+)"""),
                // AlbaPlayerControl pattern
                Regex("""AlbaPlayerControl\('([A-Za-z0-9+/=]+)','hls'\)""")
            )

            patterns.forEach { pattern ->
                pattern.findAll(iframeScripts).forEach { match ->
                    val found = match.groupValues.getOrNull(1) ?: ""
                    if (found.isNotBlank()) {
                        if (pattern.pattern.contains("youtube")) {
                            // It's a YouTube ID, construct full URL
                            val youtubeUrl = if (found.startsWith("http")) found else "https://www.youtube.com/watch?v=$found"
                            foundLinks = extractYouTubeStream(youtubeUrl, subtitleCallback, callback) || foundLinks
                        } else if (pattern.pattern.contains("AlbaPlayerControl")) {
                            // It's a base64 encoded stream URL
                            val decodedUrl = decodeBase64(found)
                            if (decodedUrl.isNotBlank()) {
                                val streamUrl = if (decodedUrl.startsWith("http")) decodedUrl else "https://$decodedUrl"
                                callback.invoke(
                                    newExtractorLink(
                                        name,
                                        "$name - بث مباشر",
                                        streamUrl,
                                        ExtractorLinkType.M3U8
                                    ) {
                                        this.referer = srcFixed
                                        this.quality = Qualities.Unknown.value
                                    }
                                )
                                foundLinks = true
                            }
                        } else {
                            val fixed = if (found.startsWith("http")) found else fixUrl(found)
                            if (fixed.contains("m3u8") || fixed.contains("mp4") || fixed.contains("stream")) {
                                loadExtractor(fixed, srcFixed, subtitleCallback, callback)
                                foundLinks = true
                            }
                        }
                    }
                }
            }

            // Method 4: Look for nested iframes
            iframeDoc.select("iframe[src]").forEach { nestedIframe ->
                val nestedSrc = fixUrl(nestedIframe.attr("src"))
                if (nestedSrc.isNotBlank()) {
                    foundLinks = extractStreamFromIframe(nestedSrc, srcFixed, subtitleCallback, callback) || foundLinks
                }
            }

            // Method 5: Try to load the iframe URL directly as a stream
            if (!foundLinks) {
                try {
                    loadExtractor(srcFixed, referer, subtitleCallback, callback)
                    foundLinks = true
                } catch (e: Exception) {
                    // Ignore
                }
            }

        } catch (e: Exception) {
            // If iframe extraction fails, try the iframe URL directly
            try {
                loadExtractor(srcFixed, referer, subtitleCallback, callback)
                foundLinks = true
            } catch (e: Exception) {
                // Ignore
            }
        }

        return foundLinks
    }

    private suspend fun tryExtractDirectLink(url: String, callback: (ExtractorLink) -> Unit) {
        try {
            val fixed = fixUrl(url)
            // Check if it's a direct video URL
            if (fixed.contains(".m3u8") || fixed.contains(".mp4") || fixed.contains(".mkv")) {
                callback.invoke(
                    newExtractorLink(
                        name,
                        "$name - بث مباشر",
                        fixed,
                        if (fixed.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
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

    // Helper function to check if URL is YouTube
    private fun isYouTubeUrl(url: String): Boolean {
        return url.contains("youtube.com") || url.contains("youtu.be")
    }

    // Helper function to extract YouTube streams
    private suspend fun extractYouTubeStream(
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            // Extract YouTube video ID
            val videoId = extractYouTubeId(url)
            if (videoId != null) {
                // Construct proper YouTube watch URL
                val youtubeUrl = "https://www.youtube.com/watch?v=$videoId"
                // Use CloudStream's YouTube extractor
                loadExtractor(youtubeUrl, mainUrl, subtitleCallback, callback)
                return true
            } else {
                // fallback: try direct URL in case url is already a watch URL
                loadExtractor(url, mainUrl, subtitleCallback, callback)
                return true
            }
        } catch (e: Exception) {
            // Try direct URL if extraction fails
            try {
                loadExtractor(url, mainUrl, subtitleCallback, callback)
                return true
            } catch (e: Exception) {
                // Ignore
            }
        }
        return false
    }

    // Helper function to extract YouTube video ID
    private fun extractYouTubeId(url: String): String? {
        val patterns = listOf(
            Regex("""youtube\.com/embed/([^?&]+)"""),
            Regex("""youtu\.be/([^?&]+)"""),
            Regex("""youtube\.com/watch\?v=([^&]+)"""),
            Regex("""youtube\.com/v/([^?&]+)""")
        )

        for (pattern in patterns) {
            pattern.find(url)?.let { match ->
                return match.groupValues[1]
            }
        }
        return null
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
