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

    // Main page categories
    override val mainPage = mainPageOf(
        "$mainUrl/player/highlights/" to "Highlights",
        "$mainUrl/player/categories/" to "Categories",
        "$mainUrl/player/binge/" to "Binge!",
        "$mainUrl/player/updates/" to "Updates"
    )

    // Fixed: Use proper category URLs from the HTML structure
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
                videoCategories.forEach { (url, name) ->
                    items.add(newMovieSearchResponse(name, url, TvType.Movie) {
                        // Using a generic icon URL
                        this.posterUrl = "$mainUrl/videos/player/favicon.ico"
                    })
                }
            }
            "Binge!" -> {
                bingeCategories.forEach { (url, name) ->
                    items.add(newMovieSearchResponse(name, url, TvType.Movie) {
                        this.posterUrl = "$mainUrl/videos/player/favicon.ico"
                    })
                }
            }
            else -> {
                val url = if (page > 1) "${request.data}?page=$page" else request.data
                val doc = app.get(url).document
                items.addAll(parseVideoItems(doc))
            }
        }
        
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    private fun parseVideoItems(doc: Element): List<SearchResponse> {
        val items = mutableListOf<SearchResponse>()
        
        // Try multiple selectors to find video items
        val selectors = listOf(
            ".swiper-slide a",
            "a.movie",
            ".video-item a",
            "article a",
            "a[href*='/player/']"
        )
        
        for (selector in selectors) {
            val elements = doc.select(selector)
            if (elements.isNotEmpty()) {
                elements.forEach { element ->
                    val href = fixUrl(element.attr("href"))
                    if (href.contains("/player/") && !href.contains("/categorie/") && !href.contains("/binge/")) {
                        val title = element.selectFirst("img")?.attr("alt") 
                            ?: element.selectFirst("span")?.text()
                            ?: element.attr("title")
                            ?: element.text().trim()
                        
                        if (title.isNotBlank()) {
                            val poster = fixUrlNull(
                                element.selectFirst("img")?.attr("src") 
                                    ?: element.selectFirst("img")?.attr("data-src")
                            )
                            
                            items.add(newMovieSearchResponse(title, href, TvType.Movie) {
                                this.posterUrl = poster
                            })
                        }
                    }
                }
                break // Use the first selector that works
            }
        }
        
        return items.distinctBy { it.url }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val searchUrl = "$mainUrl/player/?recherche=$encodedQuery"
        val doc = app.get(searchUrl).document
        
        return parseVideoItems(doc)
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        
        val title = doc.selectFirst("h1, .post__name, .entry-title, .txtblanc25")?.text()?.trim() 
            ?: "Unknown Title"
        
        val poster = fixUrlNull(
            doc.selectFirst("meta[property='og:image']")?.attr("content")
                ?: doc.selectFirst("meta[itemprop='image']")?.attr("content")
                ?: doc.selectFirst("img.lazy")?.attr("data-src")
                ?: doc.selectFirst("img")?.attr("src")
        )
        
        val description = doc.selectFirst(".description, .plot, .synopsis, .post__story")?.text()?.trim()
            ?: doc.selectFirst("meta[name='description']")?.attr("content")
        
        // Extract director and production from the HTML structure
        var director: String? = null
        var production: String? = null
        
        doc.select("span.txtorange17 b").forEach { b ->
            val text = b.text()
            val value = b.nextSibling()?.toString()?.trim()
            when {
                text.contains("Director", ignoreCase = true) && value != null -> director = value
                text.contains("Production", ignoreCase = true) && value != null -> production = value
            }
        }
        
        val tags = doc.select("a[href*='/tag/']").map { it.text().trim() }
        
        val plotBuilder = StringBuilder()
        if (director != null) plotBuilder.append("Director: $director\n")
        if (production != null) plotBuilder.append("Production: $production\n")
        if (description != null) plotBuilder.append("\n$description")
        
        // Check for episodes
        val episodes = mutableListOf<Episode>()
        
        // Check for playlist items
        doc.select(".universal_video_player_list li").forEachIndexed { index, li ->
            val videoId = li.attr("data-vimeo") ?: li.attr("data-youtube")
            val epTitle = li.attr("data-title") ?: "Episode ${index + 1}"
            
            if (videoId != null) {
                episodes.add(newEpisode(videoId) {
                    this.name = epTitle
                    this.episode = index + 1
                })
            }
        }
        
        // Check for episode links
        if (episodes.isEmpty()) {
            doc.select("a[href*='/player/']").forEachIndexed { index, a ->
                val href = fixUrl(a.attr("href"))
                val epTitle = a.text().trim()
                if (href != url && href.contains("/player/") && !href.contains("?") && epTitle.isNotBlank()) {
                    episodes.add(newEpisode(href) {
                        this.name = epTitle
                        this.episode = index + 1
                    })
                }
            }
        }
        
        if (episodes.size > 1) {
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plotBuilder.toString().trim()
                this.tags = tags
            }
        }
        
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = plotBuilder.toString().trim()
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
            // Vimeo ID
            data.matches(Regex("\\d+")) -> {
                val vimeoUrl = "https://player.vimeo.com/video/$data"
                loadExtractor(vimeoUrl, "$mainUrl/player/", subtitleCallback, callback)
                true
            }
            // YouTube ID
            data.matches(Regex("[A-Za-z0-9_-]{11}")) -> {
                val youtubeUrl = "https://www.youtube.com/watch?v=$data"
                loadExtractor(youtubeUrl, "$mainUrl/player/", subtitleCallback, callback)
                true
            }
            // Direct URL
            data.startsWith("http") -> {
                loadExtractor(data, "$mainUrl/player/", subtitleCallback, callback)
                true
            }
            else -> {
                // Try to extract video from page
                try {
                    val doc = app.get(data).document
                    
                    // Look for iframes
                    val iframe = doc.selectFirst("iframe[src*='vimeo'], iframe[src*='youtube']")
                    if (iframe != null) {
                        val src = fixUrl(iframe.attr("src"))
                        return loadExtractor(src, data, subtitleCallback, callback)
                    }
                    
                    // Look for Vimeo/YouTube in scripts
                    val scripts = doc.select("script")
                    for (script in scripts) {
                        val content = script.html()
                        
                        // Vimeo pattern
                        val vimeoPattern = Regex("vimeo\\.com/(\\d+)")
                        val vimeoMatch = vimeoPattern.find(content)
                        if (vimeoMatch != null) {
                            val vimeoId = vimeoMatch.groupValues[1]
                            val vimeoUrl = "https://player.vimeo.com/video/$vimeoId"
                            return loadExtractor(vimeoUrl, data, subtitleCallback, callback)
                        }
                        
                        // YouTube pattern
                        val youtubePattern = Regex("youtube\\.com/watch\\?v=([A-Za-z0-9_-]{11})")
                        val youtubeMatch = youtubePattern.find(content)
                        if (youtubeMatch != null) {
                            val youtubeId = youtubeMatch.groupValues[1]
                            val youtubeUrl = "https://www.youtube.com/watch?v=$youtubeId"
                            return loadExtractor(youtubeUrl, data, subtitleCallback, callback)
                        }
                    }
                    
                    false
                } catch (e: Exception) {
                    false
                }
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
