package com.letterboxed

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.letterboxed.metadata.TmdbClient
import com.letterboxed.delegation.*
import com.letterboxed.model.*

class Letterboxed : MainAPI() {

    override var name = "Letterboxed"
    override var lang = "en"
    override var mainUrl = "https://letterboxed.local"

    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val tmdb = TmdbClient()

    private val delegates = listOf(
        ArabseedDelegate(),
        AkwamDelegate(),
        CinebyDelegate()
    )

    override val mainPage = mainPageOf(
        "trending" to "Trending",
        "popular" to "Popular"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        return newHomePageResponse(request.name, emptyList())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return emptyList()
    }

    override suspend fun load(url: String): LoadResponse {
        return newMovieLoadResponse(
            name = "Unknown",
            url = url,
            type = TvType.Movie,
            dataUrl = url
        )
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val meta = tmdb.load(data)
        delegates.forEach {
            it.searchAndLoad(meta, subtitleCallback, callback)
        }
        return true
    }
}
