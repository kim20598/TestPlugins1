package com.catsuka

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLEncoder

class Catsuka : MainAPI() {
    override var mainUrl = "https://www.catsuka.com"
    override var name = "Catsuka Player"
    override var lang = "en"
    override val hasMainPage = true

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.Movie,
        TvType.ShortFilm,
        TvType.Clip
    )

    // --- MAIN PAGE — will list highlighted / featured videos ---
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = "$mainUrl/player"
        val document = app.get(url).document

        val items = document.select("div.video-item") // **must inspect real structure**
            .mapNotNull { it.toSearchResponse() }

        return newHomePageResponse(
            listOf(
                HomePageList("Featured", items)
            ),
            hasNext = false
        )
    }

    // --- SEARCH ---
    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "$mainUrl/player/search?query=$encoded"
        val document = app.get(url).document

        return document.select("div.video-item")
            .mapNotNull { it.toSearchResponse() }
    }

    // --- EXTRACT SEARCH ITEM TO RESPONSE ---
    private fun Element.toSearchResponse(): SearchResponse? {
        val href = selectFirst("a")?.attr("href")?.trim() ?: return null
        val link = fixUrl(href)
        val title = selectFirst(".title")?.text()?.trim() ?: return null
        val poster = selectFirst("img")?.attr("src")?.let { fixUrl(it) }

        return newMovieSearchResponse(title, link, TvType.Movie) {
            this.posterUrl = poster
        }
    }

    // --- LOAD VIDEO PAGE ---
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("h1, .title")?.text()?.trim() ?: "Unknown"
        val poster = document.selectFirst("img")?.attr("src")?.let { fixUrl(it) }
        val plot = document.selectFirst(".description")?.text()?.trim()

        return newMovieLoadResponse(title, url, TvType.Movie, data = url) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    // --- GET LINKS FROM EMBED PLAYER ---
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // The player page likely contains an <iframe> embed
        val doc = app.get(data).document
        val iframe = doc.selectFirst("iframe[src]")?.attr("src") ?: return false

        // loadAdapter will resolve embedded YouTube/Vimeo/Dailymotion
        loadExtractor(fixUrl(iframe), data, subtitleCallback, callback)
        return true
    }

    private fun fixUrl(url: String): String {
        return when {
            url.startsWith("http") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "$mainUrl$url"
            else -> "$mainUrl/$url"
        }
    }
}
