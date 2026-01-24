package com.animeslayer

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class AnimeSlayerWeb : MainAPI() {

    override var mainUrl = "https://animeslayerweb.com"
    override var name = "AnimeSlayer Web"
    override val supportedTypes = setOf(TvType.Anime)
    override var lang = "ar"
    override val hasMainPage = true

    // ===================== HOME =====================

    override val mainPage = mainPageOf(
        "$mainUrl/anime/" to "Latest Anime"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val doc = app.get("${request.data}?page=$page").document

        val items = doc.select("article").mapNotNull {
            it.toSearchResponse()
        }

        return HomePageResponse(
            listOf(
                HomePageList(
                    request.name,
                    items,
                    isHorizontalImages = false
                )
            ),
            hasNext = true
        )
    }

    // ===================== SEARCH =====================

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/?s=$query").document
        return doc.select("article").mapNotNull {
            it.toSearchResponse()
        }
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val a = selectFirst("a") ?: return null
        val title = selectFirst("h2, h3")?.text() ?: return null
        val poster = selectFirst("img")?.attr("src")

        return AnimeSearchResponse(
            title,
            a.attr("href"),
            this@AnimeSlayerWeb.name,
            TvType.Anime,
            poster,
            null
        )
    }

    // ===================== LOAD ANIME =====================

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document

        val title =
            doc.selectFirst("h1")?.text()
                ?: doc.selectFirst("title")?.text()?.substringBefore(" -")
                ?: "Unknown"

        val poster =
            doc.selectFirst("meta[property=og:image]")?.attr("content")

        val description =
            doc.selectFirst("meta[name=description]")?.attr("content")

        val episodes = doc.select("#EpList1 .CSB").mapIndexed { index, el ->
            val link = el.selectFirst("a")!!.attr("href")
            Episode(
                data = link,
                name = el.text().ifBlank { "Episode ${index + 1}" }
            )
        }

        return AnimeLoadResponse(
            title,
            url,
            this.name,
            TvType.Anime,
            episodes.reversed(),
            poster,
            description
        )
    }

    // ===================== STREAM =====================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val doc = app.get(data).document

        doc.select(".ul-server-position1 li").forEach {
            val link = it.attr("data-url")
            if (link.isNotBlank()) {
                loadExtractor(
                    link,
                    referer = mainUrl,
                    subtitleCallback,
                    callback
                )
            }
        }
    }
}
