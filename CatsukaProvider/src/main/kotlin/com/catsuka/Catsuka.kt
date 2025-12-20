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

    // Main page categories from the HTML you provided
    override val mainPage = mainPageOf(
        "$mainUrl/player/highlights/" to "Highlights",
        "$mainUrl/player/categories/" to "Categories",
        "$mainUrl/player/binge/" to "Binge!",
        "$mainUrl/player/updates/" to "Updates"
    )

    // Video categories from the HTML
    private val videoCategories = listOf(
        "$mainUrl/player/categorie/courtmetrage" to "Short films",
        "$mainUrl/player/categorie/pilote" to "Pilots",
        "$mainUrl/player/categorie/episode" to "Episodes",
        "$mainUrl/player/categorie/catsukatrailers" to "Movie Trailers",
        "$mainUrl/player/categorie/clip" to "Music videos",
        "$mainUrl/player/categorie/pub" to "Commercials",
        "$mainUrl/player/categorie/cinematique" to "Cinematics",
        "$mainUrl/player/categorie/nanars" to "Junk",
        "$mainUrl/player/categorie/opening" to "Openings",
        "$mainUrl/player/categorie/trailer" to "Trailers",
        "$mainUrl/player/categorie/extrait" to "Excerpts",
        "$mainUrl/player/categorie/demoreel" to "Demoreels",
        "$mainUrl/player/categorie/sakuga" to "Sakuga",
        "$mainUrl/player/categorie/makingof" to "Making Of",
        "$mainUrl/player/categorie/parodies" to "Tributes",
        "$mainUrl/player/categorie/autres" to "Others",
        "$mainUrl/player/categorie/catsukanolife" to "Catsuka TV show"
    )

    // Binge categories from the HTML
    private val bingeCategories = listOf(
        "$mainUrl/player/binge/category-animepost2000_watchable-nofilter_sort-rank/" to "Anime 2000s",
        "$mainUrl/player/binge/category-anime1990_watchable-nofilter_sort-rank/" to "Anime 1990s",
        "$mainUrl/player/binge/category-anime1980_watchable-nofilter_sort-rank/" to "Anime 1980s",
        "$mainUrl/player/binge/category-animeretro_watchable-nofilter_sort-rank/" to "Anime Retro",
        "$mainUrl/player/binge/category-usa2000_watchable-nofilter_sort-rank/" to "US 2000s",
        "$mainUrl/player/binge/category-usa8090_watchable-nofilter_sort-rank/" to "US 1980-1990",
        "$mainUrl/player/binge/category-usaretro_watchable-nofilter_sort-rank/" to "US Retro",
        "$mainUrl/player/binge/category-fr2000_watchable-nofilter_sort-rank/" to "FR 2000s",
        "$mainUrl/player/binge/category-fr8090_watchable-nofilter_sort-rank/" to "FR 1980-1990",
        "$mainUrl/player/binge/category-frretro_watchable-nofilter_sort-rank/" to "FR Retro",
        "$mainUrl/player/binge/category-other_watchable-nofilter_sort-rank/" to "Other",
        "$mainUrl/player/binge/category-nostalgiafr_watchable-nofilter_sort-rank/" to "Nostalgia (France)",
        "$mainUrl/player/binge/category-nostalgiaus_watchable-nofilter_sort-rank/" to "Nostalgia (USA)",
        "$mainUrl/player/binge/category-young_watchable-nofilter_sort-rank/" to "Young",
        "$mainUrl/player/binge/category-movieova_watchable-nofilter_sort-rank/" to "Movies & OVA",
        "$mainUrl/player/binge/category-indiesshort_watchable-nofilter_sort-rank/" to "Indies & Short formats",
        "$mainUrl/player/binge/category-all_watchable-nofilter_sort-rank/" to "ALL TITLES"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val items = mutableListOf<SearchResponse>()
        
        when (request.name) {
            "Categories" -> {
                // Show all video categories
                videoCategories.forEach { (url, name) ->
                    items.add(createCategoryItem(name, url, "category"))
                }
            }
            "Binge!" -> {
                // Show all Binge categories
                bingeCategories.forEach { (url, name) ->
                    items.add(createCategoryItem(name, url, "binge"))
                }
            }
            else -> {
                // For Highlights and Updates, scrape videos from the page
                val url = if (page > 1) "${request.data}?page=$page" else request.data
                val doc = app.get(url).document
                items.addAll(parseVideoItems(doc))
            }
        }
        
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    private fun createCategoryItem(title: String, url: String, type: String): SearchResponse {
        return newMovieSearchResponse(title, url, TvType.Movie) {
            this.posterUrl = "$mainUrl/videos/player/icon_${type}.jpg"
        }
    }

    private fun parseVideoItems(doc: Element): List<SearchResponse> {
        return doc.select("a.movie, .video-item, article, .swiper-slide a").mapNotNull { element ->
            val href = fixUrl(element.attr("href"))
            val title = element.selectFirst("img")?.attr("alt") 
                ?: element.selectFirst(".title, h3, span")?.text()
                ?: return@mapNotNull null
            
            val poster = fixUrlNull(element.selectFirst("img")?.attr("src") ?: element.selectFirst("img")?.attr("data-src"))
            
            val inferredType = when {
                href.contains("/movie/") || href.contains("/film/") -> TvType.Movie
                href.contains("/series/") || href.contains("/episode/") -> TvType.TvSeries
                href.contains("/anime/") -> TvType.Anime
                href.contains("/cartoon/") -> TvType.Cartoon
                href.contains("/ova/") -> TvType.OVA
                else -> TvType.Movie
            }
            
            newMovieSearchResponse(title, href, inferredType) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val searchUrl = "$mainUrl/player/?recherche=$encodedQuery"
        val doc = app.get(searchUrl).document
        
        return parseVideoItems(doc)
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        
        val title = doc.selectFirst("h1, .post__name, .entry-title")?.text()?.trim() 
            ?: "Unknown Title"
        
        val poster = fixUrlNull(
            doc.selectFirst("meta[property='og:image'], meta[itemprop='image']")?.attr("content")
                ?: doc.selectFirst("img.lazy, .poster img, .cover img")?.attr("src")
                ?: doc.selectFirst("img")?.attr("data-src")
        )
        
        val description = doc.selectFirst(".description, .plot, .synopsis, .post__story")?.text()?.trim()
            ?: doc.selectFirst("meta[name='description']")?.attr("content")
        
        val director = doc.selectFirst("b:contains(Director)")?.nextSibling()?.toString()?.trim()
        val production = doc.selectFirst("b:contains(Production)")?.nextSibling()?.toString()?.trim()
        
        val tags = doc.select("a[href*='/tag/'], a[href*='/categorie/']").map { it.text().trim() }
        
        val plotBuilder = StringBuilder()
        if (director != null) plotBuilder.append("Director: $director\n")
        if (production != null) plotBuilder.append("Production: $production\n")
        if (description != null) plotBuilder.append("\n$description")
        
        val hasEpisodes = doc.select(".playlist, .episodes-list, .universal_video_player_list").isNotEmpty()
        val isSeries = hasEpisodes || url.contains("/series/") || url.contains("/season/")
        
        if (isSeries) {
            val episodes = mutableListOf<Episode>()
            
            doc.select(".universal_video_player_list li").forEachIndexed { index, li ->
                val videoId = li.attr("data-vimeo") ?: li.attr("data-youtube")
                val epTitle = li.attr("data-title") ?: "Episode ${index + 1}"
                val epData = videoId ?: url
                
                episodes.add(newEpisode(epData) {
                    this.name = epTitle
                    this.episode = index + 1
                })
            }
            
            if (episodes.isEmpty()) {
                doc.select("a[href*='/player/']").forEachIndexed { index, a ->
                    val href = fixUrl(a.attr("href"))
                    val epTitle = a.text().trim()
                    if (href != url && href.contains("/player/")) {
                        episodes.add(newEpisode(href) {
                            this.name = if (epTitle.isNotEmpty()) epTitle else "Episode ${index + 1}"
                            this.episode = index + 1
                        })
                    }
                }
            }
            
            if (episodes.isNotEmpty()) {
                return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                    this.posterUrl = poster
                    this.plot = plotBuilder.toString()
                    this.tags = tags
                }
            }
        }
        
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = plotBuilder.toString()
            this.tags = tags
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return when {
            data.matches(Regex("\\d+")) -> {
                val vimeoUrl = "https://player.vimeo.com/video/$data"
                loadExtractor(vimeoUrl, "$mainUrl/player/", subtitleCallback, callback)
                true
            }
            data.matches(Regex("[A-Za-z0-9_-]{11}")) -> {
                val youtubeUrl = "https://www.youtube.com/watch?v=$data"
                loadExtractor(youtubeUrl, "$mainUrl/player/", subtitleCallback, callback)
                true
            }
            data.startsWith("http") -> {
                loadExtractor(data, "$mainUrl/player/", subtitleCallback, callback)
                true
            }
            else -> {
                val doc = app.get(data).document
                
                val iframe = doc.selectFirst("iframe[src*='vimeo'], iframe[src*='youtube']")
                if (iframe != null) {
                    val src = fixUrl(iframe.attr("src"))
                    return loadExtractor(src, data, subtitleCallback, callback)
                }
                
                val scripts = doc.select("script")
                for (script in scripts) {
                    val content = script.html()
                    
                    val vimeoMatch = Regex("vimeo.*?(\\d+)").find(content)
                    if (vimeoMatch != null) {
                        val vimeoId = vimeoMatch.groupValues[1]
                        val vimeoUrl = "https://player.vimeo.com/video/$vimeoId"
                        return loadExtractor(vimeoUrl, data, subtitleCallback, callback)
                    }
                    
                    val youtubeMatch = Regex("youtube.*?([A-Za-z0-9_-]{11})").find(content)
                    if (youtubeMatch != null) {
                        val youtubeId = youtubeMatch.groupValues[1]
                        val youtubeUrl = "https://www.youtube.com/watch?v=$youtubeId"
                        return loadExtractor(youtubeUrl, data, subtitleCallback, callback)
                    }
                }
                
                false
            }
        }
    }

    private fun fixUrl(url: String): String {
        return when {
            url.isEmpty() -> ""
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