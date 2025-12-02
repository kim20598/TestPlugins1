package com.kooralite

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLEncoder

class KooraLite : MainAPI() {
    override var mainUrl = "https://www.kooralite.live"
    override var name = "KooraLite"
    override var lang = "ar"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie)
    
    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }
    
    private fun Element.toMatchSearchResponse(): SearchResponse? {
        val link = selectFirst("a")?.attr("href") ?: return null
        val href = fixUrl(link)
        
        // Extract team names with better selectors
        val team1 = selectFirst(".TM_Team.TM1 .TM_Name, .team1 .name, .home-team .name")?.text()?.trim() ?: ""
        val team2 = selectFirst(".TM_Team.TM2 .TM_Name, .team2 .name, .away-team .name")?.text()?.trim() ?: ""
        
        if (team1.isBlank() || team2.isBlank()) return null
        
        // Clean team names
        val cleanTeam1 = team1.replace("\\s+".toRegex(), " ").trim()
        val cleanTeam2 = team2.replace("\\s+".toRegex(), " ").trim()
        val title = "$cleanTeam1 vs $cleanTeam2"
        
        // Determine match status
        val isLive = classNames().any { it.contains("live", true) }
        val isFinished = classNames().any { it.contains("finished", true) }
        
        val statusEmoji = when {
            isLive -> "🔴"
            isFinished -> "✅"
            else -> "⏳"
        }
        
        val statusText = when {
            isLive -> "مباشر"
            isFinished -> "انتهت"
            else -> "قادمة"
        }
        
        // Get match time
        val time = selectFirst(".MT_Time, .match-time, .time")?.text()?.trim() ?: ""
        
        // Get tournament
        val tournament = selectFirst(".MT_Info, .match-info, .tournament")?.let { 
            it.select("li").lastOrNull()?.text()?.trim() ?: it.text().trim()
        } ?: ""
        
        // Clean tournament name
        val cleanTournament = tournament.replace("البطولة:|بطولة:".toRegex(), "").trim()
        
        // Get team logos with better extraction
        val team1Logo = extractTeamLogo(this, 1)
        val team2Logo = extractTeamLogo(this, 2)
        
        // Choose the best poster
        val poster = when {
            team1Logo != null -> team1Logo
            team2Logo != null -> team2Logo
            else -> null
        }
        
        // Build display title
        val displayTitle = buildString {
            append(statusEmoji)
            append(" ")
            append(title)
            if (cleanTournament.isNotBlank() && cleanTournament.length < 20) {
                append(" - ")
                append(cleanTournament)
            }
            if (time.isNotBlank()) {
                append(" (")
                append(time)
                append(")")
            }
        }
        
        // Store match data
        val matchData = listOf(
            title,                   // 1 - Full title
            time,                    // 2 - Time
            cleanTournament,         // 3 - Tournament
            statusText,              // 4 - Status
            poster ?: "",            // 5 - Poster URL
            cleanTeam1,              // 6 - Team 1
            cleanTeam2,              // 7 - Team 2
            team1Logo ?: "",         // 8 - Team 1 logo
            team2Logo ?: "",         // 9 - Team 2 logo
            if (isLive) "1" else if (isFinished) "2" else "0" // 10 - Status code
        ).joinToString("|")
        
        val dataUrl = "$href|$matchData"
        
        return newMovieSearchResponse(displayTitle, dataUrl, TvType.Movie) {
            this.posterUrl = poster
        }
    }
    
    private fun extractTeamLogo(element: Element, teamNumber: Int): String? {
        val selectors = listOf(
            ".TM_Team.TM$teamNumber .TM_Logo img",
            ".team$teamNumber .logo img",
            ".home-team .logo img",
            ".away-team .logo img"
        )
        
        selectors.forEach { selector ->
            element.selectFirst(selector)?.let { img ->
                val src = img.attr("src").takeIf { it.isNotBlank() }
                    ?: img.attr("data-src").takeIf { it.isNotBlank() }
                    ?: img.attr("data-lazy-src").takeIf { it.isNotBlank() }
                
                src?.let { return fixUrl(it) }
            }
        }
        
        return null
    }
    
    override val mainPage = mainPageOf(
        "$mainUrl/" to "🔥 مباريات اليوم",
        "$mainUrl/matches-today/" to "🔴 مباريات مباشرة",
        "$mainUrl/matches-yesterday/" to "📅 مباريات الأمس",
        "$mainUrl/matches-tomorrow/" to "⏳ مباريات الغد"
    )
    
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page > 1) "${request.data}page/$page/" else request.data
        val document = app.get(url, headers = mapOf("User-Agent" to USER_AGENT)).document
        
        val items = mutableListOf<SearchResponse>()
        
        // First priority: Match cards
        document.select(".AY_Match, .match-card, .fixture").forEach { match ->
            match.toMatchSearchResponse()?.let { items.add(it) }
        }
        
        return newHomePageResponse(request.name, items.distinctBy { it.url }, hasNext = items.size >= 20)
    }
    
    override suspend fun search(query: String): List<SearchResponse> {
        if (query.length < 2) return emptyList()
        
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val searchUrl = "$mainUrl/?s=$encodedQuery"
        
        return try {
            val document = app.get(searchUrl, headers = mapOf("User-Agent" to USER_AGENT)).document
            val results = mutableListOf<SearchResponse>()
            
            document.select(".AY_Match, .match-card").forEach { match ->
                match.toMatchSearchResponse()?.let { results.add(it) }
            }
            
            results.distinctBy { it.url }
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    override suspend fun load(url: String): LoadResponse {
        // Parse the stored match data
        val parts = url.split("|")
        if (parts.size < 10) {
            // Invalid data format, try to load directly
            return loadDirectUrl(url)
        }
        
        val actualUrl = parts[0]
        val title = parts.getOrNull(1) ?: "مباراة"
        val time = parts.getOrNull(2) ?: ""
        val tournament = parts.getOrNull(3) ?: ""
        val statusText = parts.getOrNull(4) ?: ""
        val poster = parts.getOrNull(5)?.takeIf { it.isNotBlank() }
        val team1 = parts.getOrNull(6) ?: ""
        val team2 = parts.getOrNull(7) ?: ""
        val team1Logo = parts.getOrNull(8)?.takeIf { it.isNotBlank() }
        val team2Logo = parts.getOrNull(9)?.takeIf { it.isNotBlank() }
        val statusCode = parts.getOrNull(10) ?: "0"
        
        // Get fresh data from the page
        val document = try {
            app.get(actualUrl, headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Accept-Language" to "ar,en-US;q=0.7,en;q=0.3",
                "Referer" to mainUrl
            )).document
        } catch (e: Exception) {
            // If page load fails, use stored data
            return createLoadResponseFromStoredData(
                title, team1, team2, time, tournament, statusText, 
                poster, team1Logo, team2Logo, actualUrl
            )
        }
        
        // Extract stream URLs with multiple methods
        val streamData = extractStreamData(document, actualUrl)
        
        // Build clean description
        val description = buildString {
            // Match header
            append("⚽ **$team1 vs $team2**\n\n")
            
            // Match information
            append("📋 **معلومات المباراة**\n")
            append("• الفريق الأول: $team1\n")
            append("• الفريق الثاني: $team2\n")
            
            if (time.isNotBlank()) {
                append("• الوقت: $time\n")
            }
            
            if (tournament.isNotBlank()) {
                append("• البطولة: $tournament\n")
            }
            
            // Status with emoji
            val statusEmoji = when (statusCode) {
                "1" -> "🔴"
                "2" -> "✅"
                else -> "⏳"
            }
            append("• الحالة: $statusEmoji $statusText\n")
            
            // Extract additional details from page
            val details = extractMatchDetails(document)
            if (details.isNotBlank()) {
                append("\n📊 **تفاصيل إضافية**\n")
                append(details)
            }
            
            // Show available stream qualities if found
            if (streamData.qualities.isNotEmpty()) {
                append("\n🎬 **جودات البث المتاحة**\n")
                streamData.qualities.forEach { quality ->
                    append("• $quality\n")
                }
            }
        }.trim()
        
        // Prepare data for loadLinks
        val loadData = if (streamData.urls.isNotEmpty()) {
            streamData.urls.joinToString("|||")
        } else {
            actualUrl
        }
        
        // Create tags
        val tags = mutableListOf("كرة قدم", "رياضة", "مباراة")
        if (tournament.isNotBlank()) {
            tags.add(tournament)
        }
        if (statusText.contains("مباشر")) {
            tags.add("بث مباشر")
        }
        if (team1.isNotBlank()) tags.add(team1)
        if (team2.isNotBlank()) tags.add(team2)
        
        return newMovieLoadResponse(title, url, TvType.Movie, loadData) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags.distinct()
            this.year = getCurrentYear()
        }
    }
    
    private fun createLoadResponseFromStoredData(
        title: String,
        team1: String,
        team2: String,
        time: String,
        tournament: String,
        statusText: String,
        poster: String?,
        team1Logo: String?,
        team2Logo: String?,
        url: String
    ): LoadResponse {
        val cleanTitle = if (title.contains("vs")) title else "$team1 vs $team2"
        
        val description = buildString {
            append("⚽ **$team1 vs $team2**\n\n")
            append("📋 **معلومات المباراة**\n")
            append("• الفريق الأول: $team1\n")
            append("• الفريق الثاني: $team2\n")
            if (time.isNotBlank()) append("• الوقت: $time\n")
            if (tournament.isNotBlank()) append("• البطولة: $tournament\n")
            append("• الحالة: $statusText\n")
            append("\n⚠️ **ملاحظة:** جاري البحث عن روابط البث...")
        }
        
        val tags = mutableListOf("كرة قدم", "رياضة", "مباراة")
        if (tournament.isNotBlank()) tags.add(tournament)
        if (statusText.contains("مباشر")) tags.add("بث مباشر")
        
        return newMovieLoadResponse(cleanTitle, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
        }
    }
    
    private suspend fun loadDirectUrl(url: String): LoadResponse {
        val document = app.get(url, headers = mapOf("User-Agent" to USER_AGENT)).document
        
        // Try to extract title
        val title = document.selectFirst("h1")?.text()?.trim()
            ?: document.selectFirst(".entry-title")?.text()?.trim()
            ?: "بث مباشر"
        
        // Try to extract match info
        val team1 = document.selectFirst(".team1 .name, .home-team")?.text()?.trim() ?: ""
        val team2 = document.selectFirst(".team2 .name, .away-team")?.text()?.trim() ?: ""
        val matchTitle = if (team1.isNotBlank() && team2.isNotBlank()) "$team1 vs $team2" else title
        
        val time = document.selectFirst(".match-time, .time")?.text()?.trim() ?: ""
        val tournament = document.selectFirst(".tournament, .league")?.text()?.trim() ?: ""
        
        // Extract stream data
        val streamData = extractStreamData(document, url)
        
        // Build description
        val description = buildString {
            append("⚽ **$matchTitle**\n\n")
            if (team1.isNotBlank() && team2.isNotBlank()) {
                append("📋 **معلومات المباراة**\n")
                append("• الفريق الأول: $team1\n")
                append("• الفريق الثاني: $team2\n")
            }
            if (time.isNotBlank()) append("• الوقت: $time\n")
            if (tournament.isNotBlank()) append("• البطولة: $tournament\n")
            
            if (streamData.qualities.isNotEmpty()) {
                append("\n🎬 **جودات البث المتاحة**\n")
                streamData.qualities.forEach { quality ->
                    append("• $quality\n")
                }
            }
        }
        
        val loadData = if (streamData.urls.isNotEmpty()) {
            streamData.urls.joinToString("|||")
        } else {
            url
        }
        
        return newMovieLoadResponse(matchTitle, url, TvType.Movie, loadData) {
            this.plot = description.trim()
            this.tags = listOf("كرة قدم", "رياضة", "مباراة")
        }
    }
    
    private data class StreamData(
        val urls: List<String>,
        val qualities: List<String>
    )
    
    private fun extractStreamData(document: org.jsoup.nodes.Document, baseUrl: String): StreamData {
        val urls = mutableSetOf<String>()
        val qualities = mutableListOf<String>()
        
        // Method 1: Check for video elements
        document.select("video source[src], audio source[src]").forEach { source ->
            val src = source.attr("src").trim()
            if (src.isNotBlank()) {
                urls.add(fixUrl(src))
                val quality = source.attr("data-quality")?.takeIf { it.isNotBlank() }
                    ?: source.attr("title")?.takeIf { it.isNotBlank() }
                    ?: "متوسط"
                qualities.add(quality)
            }
        }
        
        // Method 2: Look for iframes
        document.select("iframe[src]").forEach { iframe ->
            val src = iframe.attr("src").trim()
            if (src.isNotBlank() && (src.contains("stream") || src.contains("video") || src.contains("player"))) {
                urls.add(fixUrl(src))
                qualities.add("بث عبر إطار")
            }
        }
        
        // Method 3: Look for streaming links
        document.select("a[href*='.m3u8'], a[href*='.mp4']").forEach { link ->
            val href = link.attr("href").trim()
            if (href.isNotBlank()) {
                urls.add(fixUrl(href))
                val quality = link.text().trim().takeIf { it.isNotBlank() } ?: "مباشر"
                qualities.add(quality)
            }
        }
        
        // Method 4: Check scripts for stream URLs
        document.select("script:not([src])").forEach { script ->
            val scriptText = script.html()
            
            // Look for m3u8 URLs
            val m3u8Pattern = Regex("""(https?://[^\s'"]*\.m3u8[^\s'"]*)""")
            m3u8Pattern.findAll(scriptText).forEach { match ->
                val url = match.groupValues[1].trim()
                if (url.isNotBlank()) {
                    urls.add(fixUrl(url))
                    qualities.add("HLS Stream")
                }
            }
            
            // Look for MP4 URLs
            val mp4Pattern = Regex("""(https?://[^\s'"]*\.mp4[^\s'"]*)""")
            mp4Pattern.findAll(scriptText).forEach { match ->
                val url = match.groupValues[1].trim()
                if (url.isNotBlank()) {
                    urls.add(fixUrl(url))
                    qualities.add("MP4 Stream")
                }
            }
        }
        
        // Method 5: Check for alkoora.live iframes specifically
        document.select("iframe[src*='alkoora.live']").forEach { iframe ->
            val src = iframe.attr("src").trim()
            if (src.isNotBlank()) {
                urls.add(fixUrl(src))
                qualities.add("بث كورة لايت")
            }
        }
        
        // Method 6: Look for stream-in.live links
        document.select("a[href*='stream-in.live']").forEach { link ->
            val href = link.attr("href").trim()
            if (href.isNotBlank()) {
                urls.add(fixUrl(href))
                qualities.add("بث مباشر")
            }
        }
        
        // If no URLs found, add the base URL as last resort
        if (urls.isEmpty() && baseUrl.contains("stream")) {
            urls.add(baseUrl)
            qualities.add("رابط المباراة")
        }
        
        return StreamData(urls.toList(), qualities.distinct())
    }
    
    private fun extractMatchDetails(document: org.jsoup.nodes.Document): String {
        val details = StringBuilder()
        
        // Look for match info table
        document.select("table").forEach { table ->
            val rows = table.select("tr")
            if (rows.size >= 3) { // Likely a match info table
                rows.forEach { row ->
                    val header = row.select("th, td:first-child").text().trim()
                    val value = row.select("td:last-child").text().trim()
                    
                    if (header.isNotBlank() && value.isNotBlank() && value != "غير معروف") {
                        when (header) {
                            "البطولة" -> details.append("• البطولة: $value\n")
                            "القناة" -> details.append("• القناة الناقلة: $value\n")
                            "التاريخ" -> details.append("• التاريخ: $value\n")
                            "الملعب" -> details.append("• الملعب: $value\n")
                            "الحكم" -> details.append("• الحكم: $value\n")
                            else -> if (header.length < 20) details.append("• $header: $value\n")
                        }
                    }
                }
                return details.toString()
            }
        }
        
        return details.toString()
    }
    
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var foundLinks = false
        
        // Split multiple URLs
        val urls = if (data.contains("|||")) {
            data.split("|||").filter { it.isNotBlank() }
        } else {
            listOf(data)
        }
        
        for (url in urls) {
            try {
                when {
                    // Direct video URLs
                    url.contains(".m3u8") -> {
                        callback.invoke(
                            ExtractorLink(
                                name,
                                "$name - بث مباشر",
                                url,
                                "",
                                ExtractorLinkType.HLS,
                                quality = Qualities.Unknown.value,
                                headers = mapOf(
                                    "User-Agent" to USER_AGENT,
                                    "Referer" to mainUrl,
                                    "Origin" to mainUrl
                                )
                            )
                        )
                        foundLinks = true
                    }
                    
                    url.contains(".mp4") -> {
                        callback.invoke(
                            ExtractorLink(
                                name,
                                "$name - فيديو",
                                url,
                                "",
                                ExtractorLinkType.VIDEO,
                                quality = Qualities.Unknown.value,
                                headers = mapOf(
                                    "User-Agent" to USER_AGENT,
                                    "Referer" to mainUrl,
                                    "Origin" to mainUrl
                                )
                            )
                        )
                        foundLinks = true
                    }
                    
                    // Iframe URLs - try Cloudstream extractors
                    url.contains("iframe") || url.contains("embed") -> {
                        try {
                            loadExtractor(url, mainUrl, subtitleCallback, callback)
                            foundLinks = true
                        } catch (e: Exception) {
                            // Try to extract from iframe page
                            try {
                                val iframeDoc = app.get(url, headers = mapOf(
                                    "User-Agent" to USER_AGENT,
                                    "Referer" to mainUrl
                                )).document
                                
                                // Look for video sources in iframe
                                iframeDoc.select("source[src]").forEach { source ->
                                    val src = source.attr("src").trim()
                                    if (src.isNotBlank()) {
                                        callback.invoke(
                                            ExtractorLink(
                                                name,
                                                "$name - من الإطار",
                                                fixUrl(src),
                                                "",
                                                if (src.contains(".m3u8")) ExtractorLinkType.HLS else ExtractorLinkType.VIDEO,
                                                quality = Qualities.Unknown.value,
                                                headers = mapOf(
                                                    "User-Agent" to USER_AGENT,
                                                    "Referer" to url
                                                )
                                            )
                                        )
                                        foundLinks = true
                                    }
                                }
                            } catch (e: Exception) {
                                // Ignore
                            }
                        }
                    }
                    
                    // Regular URLs - try Cloudstream extractors
                    else -> {
                        try {
                            loadExtractor(url, mainUrl, subtitleCallback, callback)
                            foundLinks = true
                        } catch (e: Exception) {
                            // If extraction fails, check if it's a stream URL
                            if (url.contains("stream-in.live") || url.contains("alkoora.live")) {
                                // Try to load as direct stream
                                callback.invoke(
                                    ExtractorLink(
                                        name,
                                        "$name - رابط البث",
                                        url,
                                        "",
                                        ExtractorLinkType.HLS,
                                        quality = Qualities.Unknown.value,
                                        headers = mapOf(
                                            "User-Agent" to USER_AGENT,
                                            "Referer" to mainUrl
                                        )
                                    )
                                )
                                foundLinks = true
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                // Continue with next URL
                continue
            }
        }
        
        // If no links found, try one more method
        if (!foundLinks) {
            try {
                // Try to extract from the main page directly
                val mainDoc = app.get(mainUrl, headers = mapOf("User-Agent" to USER_AGENT)).document
                mainDoc.select("iframe[src*='stream']").forEach { iframe ->
                    val src = iframe.attr("src").trim()
                    if (src.isNotBlank()) {
                        try {
                            loadExtractor(src, mainUrl, subtitleCallback, callback)
                            foundLinks = true
                        } catch (e: Exception) {
                            // Ignore
                        }
                    }
                }
            } catch (e: Exception) {
                // Ignore
            }
        }
        
        return foundLinks
    }
    
    private fun getCurrentYear(): Int {
        return java.time.LocalDate.now().year
    }
    
    private fun fixUrl(url: String): String {
        return when {
            url.isBlank() -> ""
            url.startsWith("http") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "$mainUrl$url"
            else -> "$mainUrl/$url"
        }.trim()
    }
}