package com.catsuka

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLEncoder

class Catsuka : MainAPI() {
    override var mainUrl = "https://www.catsuka.com"
    override var name = "Catsuka"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(
        TvType.AnimeMovie,
        TvType.Anime,
        TvType.OVA
    )

    private val playerUrl = "$mainUrl/player"

    override val mainPage = mainPageOf(
        playerUrl to "Catsuka Player - All Content"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get(playerUrl).document
        
        val items = document.select("a[href*='/player/']").mapNotNull { element ->
            element.toSearchResponse()
        }.distinctBy { it.url }

        return newHomePageResponse(request.name, items, hasNext = false)
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val href = attr("href")
        if (href.isBlank() || href == playerUrl) return null
        
        val fullUrl = if (href.startsWith("http")) href else "$mainUrl$href"
        
        val titleText = text().trim()
        if (titleText.isBlank()) return null
        
        val (cleanTitle, language, type) = parseTitleInfo(titleText)
        
        val tvType = when {
            type.contains("Movie", ignoreCase = true) -> TvType.AnimeMovie
            else -> TvType.Anime
        }
        
        return newMovieSearchResponse(cleanTitle, fullUrl, tvType) {
            this.posterUrl = getDefaultPoster(tvType)
        }
    }

    private fun parseTitleInfo(fullTitle: String): Triple<String, String, String> {
        val parts = fullTitle.split(" - ")
        val title = parts.firstOrNull()?.trim() ?: fullTitle
        
        val typeAndLang = parts.getOrNull(1) ?: ""
        val type = if (typeAndLang.contains("Movie")) "Movie" 
                  else if (typeAndLang.contains("Seasons")) "Seasons" 
                  else ""
        
        val langMatch = Regex("\\((.*?)\\)").find(typeAndLang)
        val language = langMatch?.groupValues?.get(1) ?: ""
        
        return Triple(title, language, type)
    }

    private fun getDefaultPoster(type: TvType): String? {
        return when (type) {
            TvType.AnimeMovie -> "https://via.placeholder.com/300x450/FF6B6B/FFFFFF?text=Anime+Movie"
            TvType.Anime -> "https://via.placeholder.com/300x450/4ECDC4/FFFFFF?text=Anime+Series"
            TvType.OVA -> "https://via.placeholder.com/300x450/45B7D1/FFFFFF?text=OVA"
            else -> null
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get(playerUrl).document
        
        return document.select("a[href*='/player/']").mapNotNull { element ->
            val title = element.text().trim()
            if (title.contains(query, ignoreCase = true)) {
                element.toSearchResponse()
            } else null
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        
        val titleElement = document.selectFirst("h1, h2, .title, h3")
        val title = titleElement?.text()?.trim() ?: "Catsuka Animation"
        
        val description = document.selectFirst("meta[name=description], .description, p")
            ?.attr("content")?.ifBlank { document.selectFirst("p")?.text() } 
            ?: "Animation work from Catsuka Player"
        
        val isMovie = title.contains("Movie", ignoreCase = true) || 
                     url.contains("movie", ignoreCase = true)
        
        if (isMovie) {
            return newMovieLoadResponse(title, url, TvType.AnimeMovie, url) {
                this.posterUrl = getDefaultPoster(TvType.AnimeMovie)
                this.plot = description
            }
        } else {
            val episodes = listOf(
                newEpisode(url) {
                    this.name = "Play"
                    this.episode = 1
                    this.season = 1
                }
            )
            
            return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
                this.posterUrl = getDefaultPoster(TvType.Anime)
                this.plot = description
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        var foundLinks = false
        
        document.select("video source[src], video[src]").forEach { source ->
            val videoUrl = source.attr("src").ifBlank { source.attr("data-src") }
            if (videoUrl.isNotBlank()) {
                foundLinks = true
                callback(
                    newExtractorLink(
                        source = name,
                        name = "Direct Video",
                        url = fixUrl(videoUrl)
                    ) {
                        this.referer = data
                        this.quality = Qualities.Unknown.value
                        this.type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 
                                   else ExtractorLinkType.VIDEO
                    }
                )
            }
        }
        
        document.select("iframe[src]").forEach { iframe ->
            val iframeSrc = iframe.attr("src")
            if (iframeSrc.isNotBlank()) {
                foundLinks = true
                loadExtractor(fixUrl(iframeSrc), data, subtitleCallback, callback)
            }
        }
        
        return foundLinks
    }

    private fun fixUrl(url: String): String {
        if (url.isBlank()) return ""
        return when {
            url.startsWith("http") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "$mainUrl$url"
            else -> url
        }
    }
}
