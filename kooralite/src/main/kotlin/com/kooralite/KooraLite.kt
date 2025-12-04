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

    private fun Element.toMatchSearchResponse(): SearchResponse? {
        val link = selectFirst("a")?.attr("href") ?: return null
        val href = fixUrl(link)

        val team1 = selectFirst(".MT_Team.TM1 .TM_Name")?.text()?.trim() ?: ""
        val team2 = selectFirst(".MT_Team.TM2 .TM_Name")?.text()?.trim() ?: ""

        if (team1.isBlank() || team2.isBlank()) return null

        val title = "$team1 vs $team2"

        val statusClass = classNames().firstOrNull { it in listOf("live", "finished", "coming-soon") } ?: ""
        val statusText = when (statusClass) {
            "live" -> "🔴 مباشر"
            "finished" -> "✅ انتهت"
            "coming-soon" -> "⏳ قادم"
            else -> {
                val txt = selectFirst(".MT_Status")?.text()?.trim()
                when {
                    txt?.contains("مباشر") == true -> "🔴 مباشر"
                    txt?.contains("انتهت") == true -> "✅ انتهت"
                    txt != null && txt.isNotBlank() -> "⏳ $txt"
                    else -> "⏳ قادم"
                }
            }
        }

        val time = selectFirst(".MT_Time")?.text()?.trim() ?: ""

        val tournament = selectFirst(".MT_Info li:last-child span")?.text()?.trim()
            ?: selectFirst(".MT_Info li")?.let { select(".MT_Info li").lastOrNull()?.selectFirst("span")?.text()?.trim() }
            ?: ""

        val team1LogoRaw = selectFirst(".MT_Team.TM1 .TM_Logo img")?.attr("src")
        val team2LogoRaw = selectFirst(".MT_Team.TM2 .TM_Logo img")?.attr("src")

        val team1Logo = team1LogoRaw?.let { if (it.startsWith("http")) it else fixUrl(it) }
        val team2Logo = team2LogoRaw?.let { if (it.startsWith("http")) it else fixUrl(it) }

        val poster = team1Logo ?: team2Logo

        val enhancedTitle = if (time.isNotBlank()) {
            "$statusText $title ($time)"
        } else {
            "$statusText $title"
        }

        val matchData = listOf(title, time, tournament, statusClass, poster ?: "", team1, team2, team1Logo ?: "", team2Logo ?: "")
            .joinToString("|")
        val dataUrl = "$href|$matchData"

        return newMovieSearchResponse(enhancedTitle, dataUrl, TvType.Movie) {
            this.posterUrl = poster
        }
    }

    private fun Element.toArticleSearchResponse(): SearchResponse? {
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

        if (request.data.contains("matches-") || request.data == "$mainUrl/") {
            document.select(".AY_Match").forEach { match ->
                match.toMatchSearchResponse()?.let { items.add(it) }
            }
        }

        if (items.isEmpty()) {
            document.select(".gr-item, article, .post").forEach { article ->
                article.toArticleSearchResponse()?.let { items.add(it) }
            }
        }

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

            document.select(".AY_Match").forEach { match ->
                match.toMatchSearchResponse()?.let { results.add(it) }
            }

            document.select(".gr-item, .search-result, article").forEach { article ->
                article.toArticleSearchResponse()?.let { results.add(it) }
            }

            results
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val parts = url.split("|")
        val actualUrl = parts[0]
        val title = parts.getOrNull(1) ?: "مباراة كرة قدم"
        val time = parts.getOrNull(2) ?: ""
        var tournament = parts.getOrNull(3) ?: ""
        val status = parts.getOrNull(4) ?: ""
        val poster = parts.getOrNull(5)
        val team1 = parts.getOrNull(6) ?: ""
        val team2 = parts.getOrNull(7) ?: ""
        val team1Logo = parts.getOrNull(8)
        val team2Logo = parts.getOrNull(9)

        val document = app.get(actualUrl).document

        val description = buildString {
            if (team1.isNotBlank() && team2.isNotBlank()) {
                append("⚽ $team1 vs $team2\n")

                if (team1Logo != null || team2Logo != null) {
                    append("\n🏁 فرق المباراة:\n")
                    if (team1Logo != null) append("• $team1\n")
                    if (team2Logo != null) append("• $team2\n")
                }
            }

            if (time.isNotBlank()) append("🕒 الوقت: $time\n")
            if (tournament.isNotBlank()) append("🏆 البطولة: $tournament\n")

            when (status) {
                "live" -> append("🔴 الحالة: البث مباشر الآن\n")
                "finished" -> append("✅ الحالة: انتهت المباراة\n")
                else -> append("⏳ الحالة: قادمة\n")
            }

            val matchTable = document.select("table.table-bordered")
            if (matchTable.isNotEmpty()) {
                append("\n📋 بطاقة المباراة:\n")
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

            document.select(".video-serv a").forEach { streamLink ->
                val streamName = streamLink.text().trim()
                if (streamName.isNotBlank()) append("📡 $streamName\n")
            }

            val servers = document.select(".video-serv a")
            if (servers.isNotEmpty()) append("\n🔗 السيرفرات المتاحة: ${servers.size}\n")
        }

        val rawStreamLinks = mutableSetOf<String>()

        document.select("iframe[src]").forEach { iframe ->
            val srcRaw = iframe.attr("src")
            val src = fixUrl(srcRaw)
            if (src.isNotBlank()) rawStreamLinks.add(src)
        }

        if (actualUrl.contains("stream-in.live") || actualUrl.contains("stream") ||
            actualUrl.contains("albaplayer") || actualUrl.contains("max.mpnh.online")
        ) {
            rawStreamLinks.add(actualUrl)
        }

        document.select("video source[src]").forEach { source ->
            val srcRaw = source.attr("src")
            val src = fixUrl(srcRaw)
            if (src.isNotBlank()) rawStreamLinks.add(src)
        }

        document.select("a[href*='stream'], a[href*='watch'], a[href*='live']").forEach { link ->
            val hrefRaw = link.attr("href")
            val href = fixUrl(hrefRaw)
            if (href.isNotBlank()) rawStreamLinks.add(href)
        }

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

        val streamLinks = rawStreamLinks.map { it.trim() }.filter { it.isNotBlank() }.map { fixUrl(it) }.toSet()

        val data = if (streamLinks.isNotEmpty()) {
            streamLinks.joinToString("|||")
        } else {
            actualUrl
        }

        return newMovieLoadResponse(title, url, TvType.Movie, data) {
            this.posterUrl = poster
            this.plot = description.trim()

            val tags = mutableListOf("كرة قدم", "رياضة", "مباراة")

            val matchTable = document.select("table.table-bordered")
            var extractedTournament = tournament

            if (matchTable.isNotEmpty()) {
                matchTable.select("tr").forEach { row ->
                    val header = row.select("th").text().trim()
                    val value = row.select("td").text().trim()

                    if (header == "البطولة" && value.isNotBlank()) {
                        extractedTournament = value
                        value.split(",").map { it.trim() }.filter { it.isNotBlank() }.forEach { tag ->
                            if (!tags.contains(tag)) tags.add(tag)
                        }
                    }

                    if (header == "اسم القناة" && value.isNotBlank() && value != "غير معروف") {
                        if (!tags.contains(value)) tags.add(value)
                    }
                }
            } else if (tournament.isNotBlank()) {
                tags.add(tournament)
            }

            if (status == "live") tags.add("بث مباشر")
            if (team1.isNotBlank()) tags.add(team1)
            if (team2.isNotBlank()) tags.add(team2)

            this.tags = tags

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

    /**
     * Updated loadLinks: Emit multiple ExtractorLink entries (one per candidate stream URL).
     * - For YouTube links, delegate to existing YouTube extractor.
     * - For direct .m3u8/.mp4 links emit newExtractorLink entries with referer and guessed quality.
     * - For non-direct links attempt loadExtractor so existing extractors can handle them.
     */
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val candidates = mutableSetOf<String>()
        var foundAny = false

        // If data contains pre-collected links (||| separated), use them
        if (data.contains("|||")) {
            data.split("|||").map { it.trim() }.filter { it.isNotBlank() }.forEach { candidates.add(fixUrl(it)) }
        } else {
            // If single url, try to parse the page and gather candidates (similar to load())
            val dataUrl = fixUrl(data)
            try {
                val doc = app.get(dataUrl).document

                doc.select("iframe[src]").forEach { iframe ->
                    val src = fixUrl(iframe.attr("src"))
                    if (src.isNotBlank()) candidates.add(src)
                }

                doc.select("video source[src]").forEach { source ->
                    val src = fixUrl(source.attr("src"))
                    if (src.isNotBlank()) candidates.add(src)
                }

                doc.select("a[href*='stream'], a[href*='watch'], a[href*='live']").forEach { link ->
                    val href = fixUrl(link.attr("href"))
                    if (href.isNotBlank()) candidates.add(href)
                }

                // extract inline script urls
                doc.select("script").forEach { script ->
                    val scriptText = script.html()
                    val patterns = listOf(
                        Regex("""(https?://[^\s'"]*\.m3u8[^\s'"]*)"""),
                        Regex("""(https?://[^\s'"]*\.mp4[^\s'"]*)"""),
                        Regex("""youtube\.com/embed/([^"'\s?&>]+)"""),
                        Regex("""youtu\.be/([^"'\s?&>]+)""")
                    )

                    patterns.forEach { p ->
                        p.findAll(scriptText).forEach { m ->
                            val g = m.groupValues.getOrNull(1) ?: ""
                            if (g.isNotBlank()) {
                                val full = if (g.startsWith("http")) g else if (p.pattern.contains("youtube")) "https://www.youtube.com/watch?v=$g" else fixUrl(g)
                                candidates.add(full)
                            }
                        }
                    }
                }

                // as final fallback, try the page itself (sometimes it's a direct player url)
                candidates.add(dataUrl)
            } catch (e: Exception) {
                // if page fetch fails, still try the raw data as candidate
                candidates.add(fixUrl(data))
            }
        }

        // Normalize and prioritize direct media links first (m3u8, mp4), then others
        val directM3u8 = candidates.filter { it.contains(".m3u8") }.toMutableList()
        val directMp4 = candidates.filter { it.contains(".mp4") && !it.contains(".m3u8") }.toMutableList()
        val youtube = candidates.filter { isYouTubeUrl(it) }.toMutableList()
        val others = candidates.filter { !it.contains(".m3u8") && !it.contains(".mp4") && !isYouTubeUrl(it) }.toMutableList()

        // Helper to emit a direct link with guessed quality and referer
        fun emitDirectLink(url: String, referer: String) {
            val (label, qVal) = guessQuality(url)
            val qualityLabel = if (label.isNotBlank()) label else "Live"
            val type = if (url.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            callback.invoke(
                newExtractorLink(
                    name,
                    "$name - $qualityLabel",
                    url,
                    type
                ) {
                    this.referer = referer
                    this.quality = qVal
                }
            )
        }

        // Emit M3U8 links first
        directM3u8.forEach { link ->
            try {
                emitDirectLink(link, mainUrl)
                foundAny = true
            } catch (e: Exception) {
                // ignore and continue
            }
        }

        // Emit MP4 links next
        directMp4.forEach { link ->
            try {
                emitDirectLink(link, mainUrl)
                foundAny = true
            } catch (e: Exception) {
                // ignore
            }
        }

        // Handle YouTube via extractor (delegates to existing extractor which will invoke callback)
        youtube.forEach { link ->
            try {
                if (extractYouTubeStream(link, subtitleCallback, callback)) foundAny = true
            } catch (e: Exception) {
                // ignore
            }
        }

        // For 'others' (iframe pages, player pages), try loadExtractor so dedicated extractors can handle them.
        // If loadExtractor fails, as a last resort emit them as generic links (unknown quality).
        others.forEach { link ->
            try {
                // try to use extractor first; many iframe providers have extractors
                loadExtractor(link, mainUrl, subtitleCallback, callback)
                foundAny = true
            } catch (e: Exception) {
                // fallback: create a generic link pointing to the page (may or may not be playable)
                try {
                    callback.invoke(
                        newExtractorLink(
                            name,
                            "$name - صفحة مصدر",
                            link,
                            ExtractorLinkType.VIDEO
                        ) {
                            this.referer = mainUrl
                            this.quality = Qualities.Unknown.value
                        }
                    )
                    foundAny = true
                } catch (e2: Exception) {
                    // ignore
                }
            }
        }

        // Final safety: if nothing emitted yet, attempt to treat data as direct link / extractor
        if (!foundAny) {
            try {
                if (isYouTubeUrl(data)) {
                    foundAny = extractYouTubeStream(data, subtitleCallback, callback)
                } else if (data.contains(".m3u8") || data.contains(".mp4")) {
                    emitDirectLink(data, mainUrl)
                    foundAny = true
                } else {
                    // try extractor on the raw data url
                    loadExtractor(fixUrl(data), mainUrl, subtitleCallback, callback)
                    foundAny = true
                }
            } catch (e: Exception) {
                // nothing more to try
            }
        }

        return foundAny
    }

    // Guess quality label and value from url or host
    private fun guessQuality(url: String): Pair<String, Int> {
        val lower = url.lowercase()

        // label heuristics
        val label = when {
            lower.contains("2160") || lower.contains("4k") || lower.contains("uhd") -> "2160p"
            lower.contains("1080") || lower.contains("fhd") || lower.contains("fullhd") -> "1080p"
            lower.contains("720") || lower.contains("hd") -> "720p"
            lower.contains("480") -> "480p"
            lower.contains("360") -> "360p"
            lower.contains("low") -> "Low"
            lower.contains("sd") -> "SD"
            lower.contains("hq") -> "HQ"
            lower.contains("live") -> "Live"
            else -> ""
        }

        val qVal = when {
            label.startsWith("2160") -> Qualities.UHD.valueOrDefault()
            label.startsWith("1080") -> Qualities.HD.valueOrDefault()
            label.startsWith("720") -> Qualities.High.valueOrDefault()
            label.startsWith("480") -> Qualities.Medium.valueOrDefault()
            label.startsWith("360") -> Qualities.Low.valueOrDefault()
            label.equals("Live", ignoreCase = true) -> Qualities.Unknown.valueOrDefault()
            else -> Qualities.Unknown.valueOrDefault()
        }

        return Pair(label, qVal)
    }

    // Helpers: YouTube and direct extraction from previous implementation

    // Function to extract streams from iframes (kept for completeness)
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
                            this.quality = guessQuality(videoUrl).second
                        }
                    )
                    foundLinks = true
                }
            }

            iframeDoc.select("iframe[src*='youtube.com'], iframe[src*='youtu.be']").forEach { iframe ->
                val src = fixUrl(iframe.attr("src"))
                if (src.isNotBlank()) {
                    foundLinks = extractYouTubeStream(src, subtitleCallback, callback) || foundLinks
                }
            }

            val iframeScripts = iframeDoc.select("script").html()

            val patterns = listOf(
                Regex("""(https?://[^\s'"]*\.m3u8[^\s'"]*)"""),
                Regex("""['"](https?://[^'"]*stream[^'"]*)['"]"""),
                Regex("""['"](https?://[^'"]*\.mp4[^'"]*)['"]"""),
                Regex("""src\s*[:=]\s*['"](https?://[^'"]+)['"]"""),
                Regex("""file\s*[:=]\s*['"](https?://[^'"]+\.m3u8)['"]"""),
                Regex("""hls\.src\s*=\s*['"]([^'"]+)['"]"""),
                Regex("""player\.src\s*=\s*['"]([^'"]+)['"]"""),
                Regex("""youtube\.com/embed/([^"']+)"""),
                Regex("""youtu\.be/([^"']+)""")
            )

            patterns.forEach { pattern ->
                pattern.findAll(iframeScripts).forEach { match ->
                    val found = match.groupValues.getOrNull(1) ?: ""
                    if (found.isNotBlank()) {
                        if (pattern.pattern.contains("youtube")) {
                            val youtubeUrl = if (found.startsWith("http")) found else "https://www.youtube.com/watch?v=$found"
                            foundLinks = extractYouTubeStream(youtubeUrl, subtitleCallback, callback) || foundLinks
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

            iframeDoc.select("iframe[src]").forEach { nestedIframe ->
                val nestedSrc = fixUrl(nestedIframe.attr("src"))
                if (nestedSrc.isNotBlank()) {
                    foundLinks = extractStreamFromIframe(nestedSrc, srcFixed, subtitleCallback, callback) || foundLinks
                }
            }

            if (!foundLinks) {
                try {
                    loadExtractor(srcFixed, referer, subtitleCallback, callback)
                    foundLinks = true
                } catch (e: Exception) {
                }
            }

        } catch (e: Exception) {
            try {
                loadExtractor(srcFixed, referer, subtitleCallback, callback)
                foundLinks = true
            } catch (e: Exception) {
            }
        }

        return foundLinks
    }

    private suspend fun tryExtractDirectLink(url: String, callback: (ExtractorLink) -> Unit) {
        try {
            val fixed = fixUrl(url)
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
        }
    }

    private fun isYouTubeUrl(url: String): Boolean {
        return url.contains("youtube.com") || url.contains("youtu.be")
    }

    private suspend fun extractYouTubeStream(
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val videoId = extractYouTubeId(url)
            if (videoId != null) {
                val youtubeUrl = "https://www.youtube.com/watch?v=$videoId"
                loadExtractor(youtubeUrl, mainUrl, subtitleCallback, callback)
                return true
            } else {
                loadExtractor(url, mainUrl, subtitleCallback, callback)
                return true
            }
        } catch (e: Exception) {
            try {
                loadExtractor(url, mainUrl, subtitleCallback, callback)
                return true
            } catch (e: Exception) {
            }
        }
        return false
    }

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

    // Extension helpers for Qualities compatibility - safe access to enum values
    private fun Qualities.Companion.valueOrDefault(): Int = try {
        this::class.java.getField("Unknown") // no-op to satisfy reflection usage
        Qualities.Unknown.value
    } catch (e: Exception) {
        Qualities.Unknown.value
    }
}
