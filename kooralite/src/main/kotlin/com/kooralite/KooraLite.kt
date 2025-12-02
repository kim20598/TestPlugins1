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
    
    private fun Element.toMatchSearchResponse(): SearchResponse? {
        val link = selectFirst("a")?.attr("href") ?: return null
        val href = fixUrl(link)
        
        // Get team names
        val team1 = selectFirst(".MT_Team.TM1 .TM_Name")?.text()?.trim() ?: ""
        val team2 = selectFirst(".MT_Team.TM2 .TM_Name")?.text()?.trim() ?: ""
        
        if (team1.isBlank() || team2.isBlank()) return null
        
        val title = "$team1 vs $team2"
        
        // Get match status
        val matchDiv = this
        val statusClass = matchDiv.classNames().firstOrNull { it in listOf("live", "finished", "comming-soon") } ?: ""
        val statusText = when (statusClass) {
            "live" -> "🔴 مباشر"
            "finished" -> "✅ انتهت"
            else -> "⏳ قادم"
        }
        
        // Get match time
        val time = selectFirst(".MT_Time")?.text()?.trim() ?: ""
        
        // Get tournament
        val tournament = selectFirst(".MT_Info li:last-child span")?.text()?.trim() ?: ""
        
        // Get team logos
        val team1Logo = selectFirst(".TM_Team.TM1 .TM_Logo img")?.attr("src")?.let { 
            if (it.startsWith("http")) it else fixUrl(it) 
        }
        
        val team2Logo = selectFirst(".TM_Team.TM2 .TM_Logo img")?.attr("src")?.let { 
            if (it.startsWith("http")) it else fixUrl(it) 
        }
        
        // Choose poster
        val poster = team1Logo ?: team2Logo
        
        // Create title
        val enhancedTitle = if (time.isNotBlank()) {
            "$statusText $title ($time)"
        } else {
            "$statusText $title"
        }
        
        // Store match data
        val matchData = "$title|$time|$tournament|$statusClass|$poster|$team1|$team2|$team1Logo|$team2Logo"
        val dataUrl = "$href|$matchData"
        
        return newMovieSearchResponse(enhancedTitle, dataUrl, TvType.Movie) {
            this.posterUrl = poster
        }
    }
    
    override val mainPage = mainPageOf(
        "$mainUrl/" to "مباريات اليوم",
        "$mainUrl/matches-today/" to "المباريات الحية",
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
        
        // Get matches
        document.select(".AY_Match").forEach { match ->
            match.toMatchSearchResponse()?.let { items.add(it) }
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
            
            document.select(".AY_Match").forEach { match ->
                match.toMatchSearchResponse()?.let { results.add(it) }
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
        val tournament = parts.getOrNull(3) ?: ""
        val status = parts.getOrNull(4) ?: ""
        val poster = parts.getOrNull(5)
        val team1 = parts.getOrNull(6) ?: ""
        val team2 = parts.getOrNull(7) ?: ""
        val team1Logo = parts.getOrNull(8)
        val team2Logo = parts.getOrNull(9)
        
        val document = app.get(actualUrl).document
        
        // Build clean description
        val description = buildString {
            if (team1.isNotBlank() && team2.isNotBlank()) {
                append("⚽ $team1 vs $team2\n")
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
                append("\n📋 معلومات المباراة:\n")
                
                matchTable.select("tr").forEach { row ->
                    val header = row.select("th").text().trim()
                    val value = row.select("td").text().trim()
                    
                    if (header.isNotBlank() && value.isNotBlank()) {
                        append("• $header: $value\n")
                    }
                }
            }
        }.trim()
        
        // Extract stream URLs from the page
        val streamUrls = extractStreamUrls(document, actualUrl)
        val data = if (streamUrls.isNotEmpty()) {
            streamUrls.joinToString("|||")
        } else {
            actualUrl
        }
        
        return newMovieLoadResponse(title, url, TvType.Movie, data) {
            this.posterUrl = poster
            this.plot = description
            
            // Add tags
            val tags = mutableListOf("كرة قدم", "رياضة", "مباراة")
            
            if (tournament.isNotBlank()) {
                tags.add(tournament)
            }
            
            if (status == "live") {
                tags.add("بث مباشر")
            }
            
            if (team1.isNotBlank()) {
                tags.add(team1)
            }
            if (team2.isNotBlank()) {
                tags.add(team2)
            }
            
            this.tags = tags
        }
    }
    
    private fun extractStreamUrls(document: org.jsoup.nodes.Document, baseUrl: String): List<String> {
        val urls = mutableListOf<String>()
        
        // Method 1: Check for video elements
        document.select("video source[src], audio source[src]").forEach { source ->
            val src = source.attr("src").trim()
            if (src.isNotBlank()) {
                urls.add(fixUrl(src))
            }
        }
        
        // Method 2: Look for iframes (including YouTube)
        document.select("iframe[src]").forEach { iframe ->
            val src = iframe.attr("src").trim()
            if (src.isNotBlank()) {
                urls.add(fixUrl(src))
            }
        }
        
        // Method 3: Look for streaming links in .video-serv
        document.select(".video-serv a[href]").forEach { link ->
            val href = link.attr("href").trim()
            if (href.isNotBlank()) {
                urls.add(fixUrl(href))
            }
        }
        
        // Method 4: Look for streaming scripts
        document.select("script:not([src])").forEach { script ->
            val scriptText = script.html()
            
            // Look for m3u8 URLs
            val m3u8Pattern = Regex("""(https?://[^\s'"]*\.m3u8[^\s'"]*)""")
            m3u8Pattern.findAll(scriptText).forEach { match ->
                val url = match.groupValues[1].trim()
                if (url.isNotBlank()) {
                    urls.add(fixUrl(url))
                }
            }
            
            // Look for MP4 URLs
            val mp4Pattern = Regex("""(https?://[^\s'"]*\.mp4[^\s'"]*)""")
            mp4Pattern.findAll(scriptText).forEach { match ->
                val url = match.groupValues[1].trim()
                if (url.isNotBlank()) {
                    urls.add(fixUrl(url))
                }
            }
        }
        
        // Method 5: Check for common stream patterns
        if (baseUrl.contains("stream") || baseUrl.contains("albaplayer")) {
            urls.add(baseUrl)
        }
        
        return urls.distinct()
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
                // Check if it's a direct video URL
                if (url.contains(".m3u8")) {
                    callback.invoke(
                        newExtractorLink(
                            name,
                            "$name - بث مباشر",
                            url,
                            mainUrl,
                            Qualities.Unknown.value,
                            type = ExtractorLinkType.HLS
                        )
                    )
                    foundLinks = true
                    continue
                }
                
                if (url.contains(".mp4") || url.contains(".mkv")) {
                    callback.invoke(
                        newExtractorLink(
                            name,
                            "$name - فيديو مباشر",
                            url,
                            mainUrl,
                            Qualities.Unknown.value,
                            type = ExtractorLinkType.VIDEO
                        )
                    )
                    foundLinks = true
                    continue
                }
                
                // Check if it's a YouTube URL
                if (url.contains("youtube.com/embed/") || url.contains("youtu.be/")) {
                    // Extract YouTube video ID
                    val videoId = extractYouTubeId(url)
                    if (videoId != null) {
                        // Create YouTube streaming URL
                        val youtubeUrl = "https://www.youtube.com/watch?v=$videoId"
                        loadExtractor(youtubeUrl, mainUrl, subtitleCallback, callback)
                        foundLinks = true
                        continue
                    }
                }
                
                // Check if it's an albaplayer page
                if (url.contains("albaplayer") || url.contains("max.mpnh.online")) {
                    foundLinks = extractFromAlbaPlayer(url, subtitleCallback, callback) || foundLinks
                    continue
                }
                
                // Try Cloudstream extractors
                try {
                    loadExtractor(url, mainUrl, subtitleCallback, callback)
                    foundLinks = true
                } catch (e: Exception) {
                    // If extraction fails, try to parse the page
                    foundLinks = extractFromStreamPage(url, subtitleCallback, callback) || foundLinks
                }
                
            } catch (e: Exception) {
                // Continue with next URL
                continue
            }
        }
        
        return foundLinks
    }
    
    private fun extractYouTubeId(url: String): String? {
        val patterns = listOf(
            Regex("""youtube\.com/embed/([^?&]+)"""),
            Regex("""youtu\.be/([^?&]+)"""),
            Regex("""youtube\.com/watch\?v=([^&]+)""")
        )
        
        patterns.forEach { pattern ->
            pattern.find(url)?.let { match ->
                return match.groupValues[1]
            }
        }
        
        return null
    }
    
    private suspend fun extractFromAlbaPlayer(
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var foundLinks = false
        
        try {
            val document = app.get(url).document
            
            // Look for iframes in albaplayer
            document.select("iframe[src]").forEach { iframe ->
                val src = iframe.attr("src").trim()
                if (src.isNotBlank()) {
                    // Check if it's YouTube
                    if (src.contains("youtube.com") || src.contains("youtu.be")) {
                        val videoId = extractYouTubeId(src)
                        if (videoId != null) {
                            val youtubeUrl = "https://www.youtube.com/watch?v=$videoId"
                            loadExtractor(youtubeUrl, url, subtitleCallback, callback)
                            foundLinks = true
                        }
                    } else {
                        // Try other extractors
                        try {
                            loadExtractor(src, url, subtitleCallback, callback)
                            foundLinks = true
                        } catch (e: Exception) {
                            // Ignore
                        }
                    }
                }
            }
            
            // Look for video sources in albaplayer
            document.select("source[src]").forEach { source ->
                val src = source.attr("src").trim()
                if (src.isNotBlank()) {
                    callback.invoke(
                        newExtractorLink(
                            name,
                            "$name - بث",
                            src,
                            url,
                            Qualities.Unknown.value,
                            type = if (src.contains(".m3u8")) ExtractorLinkType.HLS else ExtractorLinkType.VIDEO
                        )
                    )
                    foundLinks = true
                }
            }
            
        } catch (e: Exception) {
            // Ignore errors
        }
        
        return foundLinks
    }
    
    private suspend fun extractFromStreamPage(
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var foundLinks = false
        
        try {
            val document = app.get(url).document
            
            // Look for video sources
            document.select("video source[src], audio source[src]").forEach { source ->
                val src = source.attr("src").trim()
                if (src.isNotBlank()) {
                    callback.invoke(
                        newExtractorLink(
                            name,
                            "$name - بث",
                            src,
                            url,
                            Qualities.Unknown.value,
                            type = if (src.contains(".m3u8")) ExtractorLinkType.HLS else ExtractorLinkType.VIDEO
                        )
                    )
                    foundLinks = true
                }
            }
            
            // Look for iframes
            document.select("iframe[src]").forEach { iframe ->
                val src = iframe.attr("src").trim()
                if (src.isNotBlank()) {
                    // Check if it's YouTube
                    if (src.contains("youtube.com") || src.contains("youtu.be")) {
                        val videoId = extractYouTubeId(src)
                        if (videoId != null) {
                            val youtubeUrl = "https://www.youtube.com/watch?v=$videoId"
                            loadExtractor(youtubeUrl, url, subtitleCallback, callback)
                            foundLinks = true
                        }
                    } else {
                        // Try other extractors
                        try {
                            loadExtractor(src, url, subtitleCallback, callback)
                            foundLinks = true
                        } catch (e: Exception) {
                            // Ignore
                        }
                    }
                }
            }
            
        } catch (e: Exception) {
            // Ignore errors
        }
        
        return foundLinks
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
