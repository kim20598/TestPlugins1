package com.kooralite

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class KooraLite : MainAPI() {
    override var mainUrl = "https://www.kooralite.com"
    override var name = "KooraLite - كورة لايت"
    override var lang = "ar"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie) // Using Movie type instead of LiveTV
    
    // Store match data
    data class MatchInfo(
        val title: String,
        val url: String,
        val time: String,
        val league: String,
        val status: MatchStatus,
        val poster: String? = null
    )
    
    enum class MatchStatus {
        LIVE, UPCOMING, FINISHED, UNKNOWN
    }
    
    private fun parseMatchTime(timeText: String): MatchStatus {
        val lower = timeText.lowercase()
        return when {
            lower.contains("مباشر") || lower.contains("live") || lower.contains("يلعب الآن") -> MatchStatus.LIVE
            lower.contains("لم يبدأ") || lower.contains("قادم") || lower.contains("upcoming") -> MatchStatus.UPCOMING
            lower.contains("انتهت") || lower.contains("finished") || lower.contains("انتهى") -> MatchStatus.FINISHED
            else -> MatchStatus.UNKNOWN
        }
    }
    
    private fun Element.toSearchResponse(): SearchResponse? {
        val titleElement = selectFirst("h3, .match-title, .title, a[title]") ?: return null
        val title = titleElement.text().trim()
        val href = selectFirst("a")?.attr("href") ?: return null
        
        if (title.isBlank() || href.isBlank()) return null
        
        // Extract match details
        val timeElement = selectFirst(".match-time, .time, span.time")
        val leagueElement = selectFirst(".league, .competition, .tournament")
        val statusElement = selectFirst(".status, .live-badge")
        
        val time = timeElement?.text()?.trim() ?: "غير محدد"
        val league = leagueElement?.text()?.trim() ?: "دوري غير محدد"
        val status = parseMatchTime(time)
        
        // Try to get poster (team logos or match image)
        val poster = selectFirst("img")?.let { img ->
            img.attr("src").ifBlank { img.attr("data-src") }
        }
        
        // Create enhanced title with status
        val enhancedTitle = when (status) {
            MatchStatus.LIVE -> "🔴 بث مباشر: $title"
            MatchStatus.UPCOMING -> "⏳ قادم: $title"
            MatchStatus.FINISHED -> "✅ انتهت: $title"
            else -> title
        }
        
        // Store match info in URL for later use
        val matchData = "$title|$time|$league|${status.name}|$poster"
        val dataUrl = "$href|$matchData"
        
        return newMovieSearchResponse(enhancedTitle, dataUrl, TvType.Movie) {
            this.posterUrl = poster
        }
    }
    
    override val mainPage = mainPageOf(
        "$mainUrl/" to "المباريات الحية - Live Matches",
        "$mainUrl/live" to "البث المباشر الآن",
        "$mainUrl/upcoming" to "المباريات القادمة",
        "$mainUrl/finished" to "المباريات المنتهية",
        "$mainUrl/league/premier-league" to "الدوري الإنجليزي",
        "$mainUrl/league/la-liga" to "الدوري الإسباني",
        "$mainUrl/league/serie-a" to "الدوري الإيطالي",
        "$mainUrl/league/bundesliga" to "الدوري الألماني",
        "$mainUrl/league/ligue-1" to "الدوري الفرنسي",
        "$mainUrl/league/saudi-league" to "الدوري السعودي",
        "$mainUrl/league/egyptian-league" to "الدوري المصري",
        "$mainUrl/league/champions-league" to "دوري أبطال أوروبا",
        "$mainUrl/league/europa-league" to "الدوري الأوروبي",
        "$mainUrl/league/world-cup" to "كأس العالم",
        "$mainUrl/league/african-cup" to "كأس الأمم الأفريقية",
        "$mainUrl/league/asian-cup" to "كأس آسيا"
    )
    
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page > 1) "${request.data}?page=$page" else request.data
        val document = app.get(url).document
        
        val matches = document.select(".match-item, .live-match, .match, article.match, div.match").mapNotNull {
            it.toSearchResponse()
        }
        
        return newHomePageResponse(request.name, matches, hasNext = matches.isNotEmpty())
    }
    
    override suspend fun search(query: String): List<SearchResponse> {
        if (query.length < 2) return emptyList()
        
        val searchUrl = "$mainUrl/?s=${query.replace(" ", "+")}"
        val document = app.get(searchUrl).document
        
        return document.select(".match-item, .search-result, article").mapNotNull {
            it.toSearchResponse()
        }
    }
    
    override suspend fun load(url: String): LoadResponse {
        // Parse stored data from URL
        val parts = url.split("|")
        val actualUrl = parts[0]
        val title = parts.getOrNull(1) ?: "مباراة غير معروفة"
        val matchTime = parts.getOrNull(2) ?: "غير محدد"
        val league = parts.getOrNull(3) ?: "دوري غير محدد"
        val status = parts.getOrNull(4) ?: "UNKNOWN"
        val poster = parts.getOrNull(5)
        
        // Fetch additional details from the page
        val document = app.get(actualUrl).document
        
        // Get detailed description
        val description = buildString {
            append("⚽ $title\n")
            append("🏆 $league\n")
            append("🕒 $matchTime\n")
            append("\nمباراة كورة لايت - بث مباشر\n")
            
            // Add status indicator
            when (status) {
                "LIVE" -> append("🔴 حالة: البث مباشر الآن\n")
                "UPCOMING" -> append("⏳ حالة: قادمة قريباً\n")
                "FINISHED" -> append("✅ حالة: انتهت المباراة\n")
            }
            
            // Try to get teams info
            val teams = document.select(".teams, .team-names, .participants")
            if (teams.isNotEmpty()) {
                append("\nالفريقان:\n")
                teams.forEach { team ->
                    append("• ${team.text().trim()}\n")
                }
            }
            
            // Try to get score
            val score = document.select(".score, .result, .goals").firstOrNull()
            score?.let {
                append("\nالنتيجة: ${it.text().trim()}\n")
            }
        }
        
        // Get live stream links from the page
        val streamLinks = document.select("a[href*='stream'], a[href*='live'], iframe[src*='stream']")
            .mapNotNull { it.attr("href").ifBlank { it.attr("src") } }
            .filter { it.isNotBlank() }
            .toSet()
        
        // Create episode data with all stream links
        val episodeData = streamLinks.joinToString("|||")
        
        return newMovieLoadResponse(
            title,
            url,
            TvType.Movie,
            episodeData
        ) {
            this.posterUrl = poster
            this.plot = description.trim()
            
            // Add relevant metadata
            // Note: Using TvType.Movie doesn't have showStatus property
            // If you need status, you might need to create a custom LoadResponse
            
            // Add tags for filtering
            this.tags = listOf("كرة قدم", "مباراة", "رياضة", "بث مباشر", league)
            
            // Add recommendations (other matches)
            val recommendations = document.select(".related-matches a").mapNotNull { related ->
                val recTitle = related.text().trim()
                val recUrl = related.attr("href")
                if (recTitle.isNotBlank() && recUrl.isNotBlank()) {
                    newMovieSearchResponse(recTitle, recUrl, TvType.Movie) {
                        this.posterUrl = poster
                    }
                } else null
            }
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
        
        // Split the stored stream links
        val streamLinks = data.split("|||").filter { it.isNotBlank() }
        
        streamLinks.forEach { streamUrl ->
            try {
                // Try to extract from the stream URL
                loadExtractor(streamUrl, mainUrl, subtitleCallback, callback)
                foundLinks = true
                
            } catch (e: Exception) {
                // Try alternative extraction methods
                val doc = app.get(streamUrl, referer = mainUrl).document
                
                // Method 1: Look for iframes
                doc.select("iframe[src]").forEach { iframe ->
                    val iframeSrc = iframe.attr("src")
                    if (iframeSrc.isNotBlank()) {
                        loadExtractor(iframeSrc, streamUrl, subtitleCallback, callback)
                        foundLinks = true
                    }
                }
                
                // Method 2: Look for video sources
                doc.select("video source[src], source[src]").forEach { source ->
                    val videoUrl = source.attr("src")
                    if (videoUrl.isNotBlank()) {
                        val quality = source.attr("label").ifBlank { source.attr("title") }.ifBlank { "مباشر" }
                        
                        callback(
                            newExtractorLink(
                                source = name,
                                name = "$name - $quality",
                                url = videoUrl,
                                type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            ) {
                                this.referer = streamUrl
                                this.quality = getQualityFromName(quality)
                            }
                        )
                        foundLinks = true
                    }
                }
                
                // Method 3: Look for embed scripts
                doc.select("script").forEach { script ->
                    val scriptText = script.html()
                    // Look for common streaming patterns
                    val patterns = listOf(
                        Regex("""['"](https?://[^'"]*\.m3u8[^'"]*)['"]"""),
                        Regex("""src\s*:\s*['"](https?://[^'"]*)['"]"""),
                        Regex("""file\s*:\s*['"](https?://[^'"]*)['"]"""),
                        Regex("""hls\.src\s*=\s*['"]([^'"]*)['"]""")
                    )
                    
                    patterns.forEach { pattern ->
                        pattern.findAll(scriptText).forEach { match ->
                            val videoUrl = match.groupValues[1]
                            if (videoUrl.isNotBlank() && (videoUrl.contains("m3u8") || videoUrl.contains("mp4"))) {
                                loadExtractor(videoUrl, streamUrl, subtitleCallback, callback)
                                foundLinks = true
                            }
                        }
                    }
                }
            }
        }
        
        // If no links found, try to find on the main page with the match
        if (!foundLinks) {
            try {
                // Extract actual match URL from data
                val matchUrl = if (data.contains("http")) {
                    data.split("|||").firstOrNull { it.startsWith("http") } ?: return false
                } else {
                    // Parse from the combined URL format
                    data.split("|").firstOrNull() ?: return false
                }
                
                val matchDoc = app.get(matchUrl, referer = mainUrl).document
                
                // Look for common streaming containers
                val streamingContainers = listOf(
                    "#stream-links", ".streams-container", ".live-streams", 
                    ".embed-container", ".player-container", "#player"
                )
                
                streamingContainers.forEach { selector ->
                    matchDoc.select("$selector a[href], $selector iframe[src]").forEach { element ->
                        val streamLink = element.attr("href").ifBlank { element.attr("src") }
                        if (streamLink.isNotBlank() && streamLink.startsWith("http")) {
                            loadExtractor(streamLink, matchUrl, subtitleCallback, callback)
                            foundLinks = true
                        }
                    }
                }
                
            } catch (e: Exception) {
                // Last resort fallback
            }
        }
        
        return foundLinks
    }
}