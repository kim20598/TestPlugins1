package com.kooralite

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.net.URI

class BeinLive : MainAPI() {

    override var mainUrl = "https://www.bein-live.com"
    override var name = "Bein Live - بين لايف"
    override var lang = "ar"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie)

    private val customPosterUrl =
        "https://raw.githubusercontent.com/kim20598/TestPlugins1/master/beinlive/poster.png"

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
        val link = selectFirst("a")?.attr("href") ?: return null
        val href = fixUrl(link)

        val team1 = selectFirst(".MT_Team.TM1 .TM_Name")?.text()?.trim().orEmpty()
        val team2 = selectFirst(".MT_Team.TM2 .TM_Name")?.text()?.trim().orEmpty()
        if (team1.isBlank() || team2.isBlank()) return null

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

        val packedData = listOf(
            team1,
            team2,
            time,
            tournament,
            statusClass,
            broadcast
        ).joinToString("|")

        return newMovieSearchResponse(title, "$href|$packedData", TvType.Movie) {
            posterUrl = customPosterUrl
        }
    }

    // ========================= Main Page =========================

    override val mainPage = mainPageOf(
        "$mainUrl/" to "الرئيسية",
        "$mainUrl/matches-today_1/" to "مباريات اليوم",
        "$mainUrl/matches-yesterday/" to "مباريات الأمس",
        "$mainUrl/matches-tomorrow/" to "مباريات الغد",
        "$mainUrl/home_1/" to "المباريات الحية"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val url = if (page > 1) "${request.data}page/$page/" else request.data
        val document = app.get(url).document

        val items = document.select(".albaflex .AY_Match, .AY_Match")
            .mapNotNull { it.toMatchSearchResponse() }

        return newHomePageResponse(request.name, items, hasNext = true)
    }

    // ========================= Search =========================

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.length < 2) return emptyList()

        val searchUrl = "$mainUrl/?s=${URLEncoder.encode(query, "UTF-8")}"
        return app.get(searchUrl).document
            .select(".AY_Match, .match-item, article")
            .mapNotNull { it.toMatchSearchResponse() }
    }

    // ========================= Load =========================

    override suspend fun load(url: String): LoadResponse {
        val parts = url.split("|")
        val pageUrl = parts[0]

        val team1 = parts.getOrNull(1).orEmpty()
        val team2 = parts.getOrNull(2).orEmpty()
        val time = parts.getOrNull(3).orEmpty()
        val tournament = parts.getOrNull(4).orEmpty()
        val status = parts.getOrNull(5).orEmpty()
        val broadcast = parts.getOrNull(6).orEmpty()

        val description = buildString {
            append("⚽ $team1 vs $team2\n")
            if (time.isNotBlank()) append("🕒 الوقت: $time\n")
            if (broadcast.isNotBlank()) append("📺 القناة: $broadcast\n")
            if (tournament.isNotBlank()) append("🏆 البطولة: $tournament\n")
            when (status) {
                "live" -> append("🔴 بث مباشر الآن")
                "finished" -> append("✅ انتهت المباراة")
                else -> append("⏳ قادمة")
            }
        }

        return newMovieLoadResponse(
            "$team1 vs $team2",
            url,
            TvType.Movie,
            pageUrl
        ) {
            posterUrl = customPosterUrl
            plot = description
        }
    }

    // ========================= Load Links =========================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val pageUrl = fixUrl(data.substringBefore("|"))
        val document = app.get(pageUrl).document
        val visited = mutableSetOf<String>()
        var foundLinks = false

        document.select("iframe[src], .video-serv a[href]").forEach {
            val src = fixUrl(it.attr("src").ifBlank { it.attr("href") })
            if (src.isNotBlank() && visited.add(src)) {
                foundLinks = extractAllAlbaPlayerStreams(
                    src,
                    callback,
                    visited
                ) || foundLinks
            }
        }

        return foundLinks
    }

    // ========================= AlbaPlayer Extractor =========================

    private suspend fun extractAllAlbaPlayerStreams(
        url: String,
        callback: (ExtractorLink) -> Unit,
        visited: MutableSet<String>
    ): Boolean {

        val html = app.get(url).text
        var found = false

        // AlbaPlayerControl
        Regex("""AlbaPlayerControl\(['"]([^'"]+)['"],['"](hls|plyr)['"]\)""")
            .findAll(html)
            .forEach {
                val decoded = decodeBase64(it.groupValues[1])
                val streamUrl =
                    if (decoded.startsWith("http")) decoded else "https://$decoded"

                callback(
                    newExtractorLink(
                        name,
                        "AlbaPlayer",
                        streamUrl,
                        ExtractorLinkType.M3U8
                    ) {
                        referer = url
                        quality = Qualities.Unknown.value
                    }
                )
                found = true
            }

        // Direct m3u8
        Regex("""https?://[^\s"'<>]+\.m3u8""")
            .findAll(html)
            .forEach {
                callback(
                    newExtractorLink(
                        name,
                        "Direct",
                        it.value,
                        ExtractorLinkType.M3U8
                    ) {
                        referer = url
                        quality = Qualities.Unknown.value
                    }
                )
                found = true
            }

        // Server menu (safe recursion)
        val baseDomain = runCatching {
            URI(url).let { "${it.scheme}://${it.host}" }
        }.getOrElse { "" }

        Regex("""href=["']([^"']+)["']""")
            .findAll(html)
            .forEach {
                val link = it.groupValues[1]
                val fullUrl =
                    if (link.startsWith("http")) link else "$baseDomain$link"

                if (visited.add(fullUrl)) {
                    found = extractAllAlbaPlayerStreams(
                        fullUrl,
                        callback,
                        visited
                    ) || found
                }
            }

        return found
    }
}
