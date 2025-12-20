package com.catsuka.provider

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLEncoder

class Catsuka : MainAPI() {
    override var mainUrl = "https://www.catsuka.com"
    override var name = "Catsuka Player"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.Cartoon,
        TvType.OVA
    )

    // SIMPLE: Just use the main page and highlights
    override val mainPage = mainPageOf(
        "$mainUrl/player/" to "Catsuka Videos",
        "$mainUrl/player/highlights/" to "Highlights",
        "$mainUrl/player/updates/" to "Latest Updates"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = request.data
        val document = app.get(url).document
        
        val home = document.select("a").mapNotNull { it.toSearchResult() }
        
        return newHomePageResponse(request.name, home.distinctBy { it.url }, hasNext = true)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = this.attr("href") ?: return null
        if (!href.contains("/player/") || href.contains("cgu") || href.contains("privacy")) {
            return null
        }
        
        val title = this.selectFirst("img")?.attr("alt") 
            ?: this.text().trim()
            ?: return null
            
        if (title.isEmpty() || title.contains("CGU") || title.contains("Privacy")) {
            return null
        }
        
        val posterUrl = fixUrlNull(
            this.selectFirst("img")?.attr("src")?.takeIf { it.isNotBlank() }
            ?: this.selectFirst("img")?.attr("data-src")
        )
        
        return newAnimeSearchResponse(title, fixUrl(href)) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val document = app.get("$mainUrl/player/?recherche=$encodedQuery").document
            
            document.select("a").mapNotNull { it.toSearchResult() }
                .distinctBy { it.url }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        return try {
            val document = app.get(url).document
            
            val title = document.selectFirst("h1, .title, h2")?.text()?.trim() ?: "Unknown Title"
            val poster = document.selectFirst("img")?.attr("src")
                ?.let { if (it.startsWith("http")) it else "$mainUrl$it" }
            val plot = document.selectFirst("p, .description")?.text()?.trim()
            
            // Simple: always return as movie for now
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
            }
            
        } catch (e: Exception) {
            newMovieLoadResponse("Error", url, TvType.Movie, url) {
                this.plot = "Failed to load: ${e.message}"
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            // If data is a URL, get the page and look for iframes
            if (data.startsWith("http")) {
                val document = app.get(data).document
                
                // Look for iframe
                val iframe = document.selectFirst("iframe[src]")
                val iframeSrc = iframe?.attr("src")?.takeIf { it.isNotBlank() }
                    ?.let { if (it.startsWith("http")) it else "https:$it" }
                
                if (iframeSrc != null) {
                    return loadExtractor(iframeSrc, subtitleCallback, callback)
                }
            }
            
            false
        } catch (e: Exception) {
            false
        }
    }

    private fun fixUrl(url: String): String {
        if (url.isBlank()) return ""
        return when {
            url.startsWith("http") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "$mainUrl$url"
            else -> "$mainUrl/$url"
        }
    }

    private fun fixUrlNull(url: String?): String? {
        return url?.let { fixUrl(it) }
    }
}
