package com.animeslayer

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLEncoder

class AnimeSlayer : MainAPI() {
    override var mainUrl = "https://animeslayerweb.com"
    override var name = "AnimeSlayer"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    override val mainPage = mainPageOf(
        "$mainUrl/anime-slayer-home/" to "الصفحة الرئيسية",
        "$mainUrl/anime/?status=ongoing" to "الأنمي المستمر",
        "$mainUrl/anime/?status=completed&order=rating" to "الأعلى تقييماً",
        "$mainUrl/anime/?status=completed" to "الأنمي المكتمل"
    )

    // Add filter support
    override val usePagination = true
    
    // Genre filter list
    private val genres = listOf(
        Pair("action", "Action"),
        Pair("adult-cast", "Adult Cast"),
        Pair("adventure", "Adventure"),
        Pair("anthropomorphic", "Anthropomorphic"),
        Pair("avant-garde", "Avant Garde"),
        Pair("award-winning", "Award Winning"),
        Pair("boys-love", "Boys Love"),
        Pair("cgdct", "CGDCT"),
        Pair("childcare", "Childcare"),
        Pair("combat-sports", "Combat Sports"),
        Pair("comedy", "Comedy"),
        Pair("crossdressing", "Crossdressing"),
        Pair("delinquents", "Delinquents"),
        Pair("detective", "Detective"),
        Pair("drama", "Drama"),
        Pair("ecchi", "Ecchi"),
        Pair("educational", "Educational"),
        Pair("erotica", "Erotica"),
        Pair("fantasy", "Fantasy"),
        Pair("gag-humor", "Gag Humor"),
        Pair("girls-love", "Girls Love"),
        Pair("gore", "Gore"),
        Pair("gourmet", "Gourmet"),
        Pair("harem", "Harem"),
        Pair("high-stakes-game", "High Stakes Game"),
        Pair("historical", "Historical"),
        Pair("horror", "Horror"),
        Pair("idols-female", "Idols (Female)"),
        Pair("idols-male", "Idols (Male)"),
        Pair("isekai", "Isekai"),
        Pair("iyashikei", "Iyashikei"),
        Pair("josei", "Josei"),
        Pair("kids", "Kids"),
        Pair("love-polygon", "Love Polygon"),
        Pair("love-status-quo", "Love Status Quo"),
        Pair("magical-sex-shift", "Magical Sex Shift"),
        Pair("mahou-shoujo", "Mahou Shoujo"),
        Pair("martial-arts", "Martial Arts"),
        Pair("mecha", "Mecha"),
        Pair("medical", "Medical"),
        Pair("military", "Military"),
        Pair("music", "Music"),
        Pair("mystery", "Mystery"),
        Pair("mythology", "Mythology"),
        Pair("organized-crime", "Organized Crime"),
        Pair("otaku-culture", "Otaku Culture"),
        Pair("parody", "Parody"),
        Pair("performing-arts", "Performing Arts"),
        Pair("pets", "Pets"),
        Pair("psychological", "Psychological"),
        Pair("racing", "Racing"),
        Pair("reincarnation", "Reincarnation"),
        Pair("reverse-harem", "Reverse Harem"),
        Pair("romance", "Romance"),
        Pair("romantic-subtext", "Romantic Subtext"),
        Pair("samurai", "Samurai"),
        Pair("school", "School"),
        Pair("sci-fi", "Sci-Fi"),
        Pair("seinen", "Seinen"),
        Pair("shoujo", "Shoujo"),
        Pair("shounen", "Shounen"),
        Pair("showbiz", "Showbiz"),
        Pair("slice-of-life", "Slice of Life"),
        Pair("space", "Space"),
        Pair("sports", "Sports"),
        Pair("strategy-game", "Strategy Game"),
        Pair("super-power", "Super Power"),
        Pair("supernatural", "Supernatural"),
        Pair("survival", "Survival"),
        Pair("suspense", "Suspense"),
        Pair("team-sports", "Team Sports"),
        Pair("time-travel", "Time Travel"),
        Pair("urban-fantasy", "Urban Fantasy"),
        Pair("vampire", "Vampire"),
        Pair("video-game", "Video Game"),
        Pair("villainess", "Villainess"),
        Pair("visual-arts", "Visual Arts"),
        Pair("workplace", "Workplace")
    )
    
    // Season filter list
    private val seasons = listOf(
        Pair("fall-1992", "Fall 1992"),
        Pair("fall-1993", "Fall 1993"),
        Pair("fall-1999", "Fall 1999"),
        Pair("fall-2000", "Fall 2000"),
        Pair("fall-2002", "Fall 2002"),
        Pair("fall-2004", "Fall 2004"),
        Pair("fall-2006", "Fall 2006"),
        Pair("fall-2007", "Fall 2007"),
        Pair("fall-2008", "Fall 2008"),
        Pair("fall-2009", "Fall 2009"),
        Pair("fall-2010", "Fall 2010"),
        Pair("fall-2011", "Fall 2011"),
        Pair("fall-2012", "Fall 2012"),
        Pair("fall-2013", "Fall 2013"),
        Pair("fall-2014", "Fall 2014"),
        Pair("fall-2015", "Fall 2015"),
        Pair("fall-2016", "Fall 2016"),
        Pair("fall-2017", "Fall 2017"),
        Pair("fall-2018", "Fall 2018"),
        Pair("fall-2019", "Fall 2019"),
        Pair("fall-2020", "Fall 2020"),
        Pair("fall-2021", "Fall 2021"),
        Pair("fall-2022", "Fall 2022"),
        Pair("fall-2023", "Fall 2023"),
        Pair("fall-2024", "Fall 2024"),
        Pair("fall-2025", "Fall 2025"),
        Pair("spring-1978", "Spring 1978"),
        Pair("spring-1998", "Spring 1998"),
        Pair("spring-2004", "Spring 2004"),
        Pair("spring-2006", "Spring 2006"),
        Pair("spring-2007", "Spring 2007"),
        Pair("spring-2008", "Spring 2008"),
        Pair("spring-2009", "Spring 2009"),
        Pair("spring-2010", "Spring 2010"),
        Pair("spring-2011", "Spring 2011"),
        Pair("spring-2012", "Spring 2012"),
        Pair("spring-2013", "Spring 2013"),
        Pair("spring-2014", "Spring 2014"),
        Pair("spring-2015", "Spring 2015"),
        Pair("spring-2016", "Spring 2016"),
        Pair("spring-2017", "Spring 2017"),
        Pair("spring-2018", "Spring 2018"),
        Pair("spring-2019", "Spring 2019"),
        Pair("spring-2020", "Spring 2020"),
        Pair("spring-2021", "Spring 2021"),
        Pair("spring-2022", "Spring 2022"),
        Pair("spring-2023", "Spring 2023"),
        Pair("spring-2024", "Spring 2024"),
        Pair("spring-2025", "Spring 2025"),
        Pair("summer-1999", "Summer 1999"),
        Pair("summer-2007", "Summer 2007"),
        Pair("summer-2008", "Summer 2008"),
        Pair("summer-2009", "Summer 2009"),
        Pair("summer-2010", "Summer 2010"),
        Pair("summer-2011", "Summer 2011"),
        Pair("summer-2012", "Summer 2012"),
        Pair("summer-2013", "Summer 2013"),
        Pair("summer-2014", "Summer 2014"),
        Pair("summer-2015", "Summer 2015"),
        Pair("summer-2016", "Summer 2016"),
        Pair("summer-2017", "Summer 2017"),
        Pair("summer-2018", "Summer 2018"),
        Pair("summer-2019", "Summer 2019"),
        Pair("summer-2020", "Summer 2020"),
        Pair("summer-2021", "Summer 2021"),
        Pair("summer-2022", "Summer 2022"),
        Pair("summer-2023", "Summer 2023"),
        Pair("summer-2024", "Summer 2024"),
        Pair("summer-2025", "Summer 2025"),
        Pair("winter-1986", "Winter 1986"),
        Pair("winter-1996", "Winter 1996"),
        Pair("winter-2007", "Winter 2007"),
        Pair("winter-2009", "Winter 2009"),
        Pair("winter-2010", "Winter 2010"),
        Pair("winter-2011", "Winter 2011"),
        Pair("winter-2012", "Winter 2012"),
        Pair("winter-2013", "Winter 2013"),
        Pair("winter-2014", "Winter 2014"),
        Pair("winter-2015", "Winter 2015"),
        Pair("winter-2016", "Winter 2016"),
        Pair("winter-2017", "Winter 2017"),
        Pair("winter-2018", "Winter 2018"),
        Pair("winter-2019", "Winter 2019"),
        Pair("winter-2020", "Winter 2020"),
        Pair("winter-2021", "Winter 2021"),
        Pair("winter-2022", "Winter 2022"),
        Pair("winter-2023", "Winter 2023"),
        Pair("winter-2024", "Winter 2024"),
        Pair("winter-2025", "Winter 2025"),
        Pair("winter-2026", "Winter 2026")
    )
    
    // Status filter list
    private val statuses = listOf(
        Pair("ongoing", "مستمر"),
        Pair("completed", "مكتمل"),
        Pair("upcoming", "قادم"),
        Pair("hiatus", "توقف مؤقت")
    )
    
    // Type filter list
    private val types = listOf(
        Pair("tv", "مسلسل تلفزيوني"),
        Pair("ova", "OVA"),
        Pair("movie", "فيلم"),
        Pair("live action", "لايف أكشن"),
        Pair("special", "خاص"),
        Pair("bd", "BD"),
        Pair("ona", "ONA"),
        Pair("music", "موسيقى")
    )
    
    // Order by filter list
    private val orders = listOf(
        Pair("", "الافتراضي"),
        Pair("title", "أ-ي"),
        Pair("titlereverse", "ي-أ"),
        Pair("update", "أحدث تحديث"),
        Pair("latest", "أحدث إضافة"),
        Pair("popular", "شائع"),
        Pair("rating", "التقييم")
    )

    // Parse anime cards - UPDATED to avoid duplicates
    private fun Element.toSearchResult(): SearchResponse? {
        // Get the main container (either article.bs or .bsx)
        val isArticle = this.hasClass("bs")
        val card = if (isArticle) this.selectFirst(".bsx") ?: this else this
        
        val href = card.selectFirst("a")?.attr("href") ?: return null
        val title = card.selectFirst(".tt h2, .tt, h2")?.text()?.trim() 
            ?: card.selectFirst("img")?.attr("alt")?.trim()
            ?: return null
        
        // Check if this is a duplicate by checking URL
        if (href.isBlank()) return null
        
        val poster = card.selectFirst("img")?.attr("src")?.let { fixUrl(it) }
        
        // Get type from the div
        val typeClass = card.selectFirst(".typez")?.text()?.lowercase() 
            ?: card.selectFirst(".typez")?.attr("class")?.lowercase()
        
        val type = when {
            typeClass?.contains("movie") == true -> TvType.AnimeMovie
            typeClass?.contains("ova") == true -> TvType.OVA
            else -> TvType.Anime
        }
        
        return newAnimeSearchResponse(title, fixUrl(href)) {
            this.posterUrl = poster
            this.type = type
        }
    }

    // Add filter support
    override fun getFilterList(): FilterList {
        return FilterList(
            Filter.Header("⚠️ إضغط بحث بعد تحديد الفلاتر"),
            Filter.Separator(),
            Filter.Header("النوع (Genres)"),
            Filter.CheckGroup("Genres", genres.map { CheckBoxPair(it.second, it.first) }),
            Filter.Separator(),
            Filter.Header("الموسم (Season)"),
            Filter.CheckGroup("Seasons", seasons.map { CheckBoxPair(it.second, it.first) }),
            Filter.Separator(),
            Filter.Header("الحالة (Status)"),
            Filter.Select("Status", statuses.map { SelectOption(it.second, it.first) }),
            Filter.Separator(),
            Filter.Header("نوع الأنمي (Type)"),
            Filter.Select("Type", types.map { SelectOption(it.second, it.first) }),
            Filter.Separator(),
            Filter.Header("ترتيب حسب (Order By)"),
            Filter.Select("Order", orders.map { SelectOption(it.second, it.first) }),
            Filter.Separator(),
        )
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data}page/$page/"
        val doc = app.get(url).document
        
        // FIXED: Only select one type of container to avoid duplicates
        val items = mutableListOf<SearchResponse>()
        val seenUrls = mutableSetOf<String>()
        
        // Try different container selectors in priority order
        val selectors = listOf(
            ".listupd article.bs",  // Primary selector for list pages
            "article.bs .bsx",      // Nested bsx inside article
            ".bsx",                 // Direct bsx elements
            ".listupd .bsx",        // bsx inside listupd
            "article.bs"            // Fallback to article
        )
        
        // Try each selector until we get items
        for (selector in selectors) {
            val elements = doc.select(selector)
            if (elements.isNotEmpty()) {
                elements.forEach { element ->
                    val result = element.toSearchResult()
                    if (result != null && !seenUrls.contains(result.url)) {
                        seenUrls.add(result.url)
                        items.add(result)
                    }
                }
                // If we found items, break (don't try other selectors)
                if (items.isNotEmpty()) {
                    break
                }
            }
        }
        
        // If still no items, try a broader search
        if (items.isEmpty()) {
            val allAnimeElements = doc.select("article, .bsx, .anime-item")
            allAnimeElements.forEach { element ->
                val result = element.toSearchResult()
                if (result != null && !seenUrls.contains(result.url)) {
                    seenUrls.add(result.url)
                    items.add(result)
                }
            }
        }
        
        return newHomePageResponse(request.name, items, items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val doc = app.get("$mainUrl/?s=$encoded").document
        
        val items = mutableListOf<SearchResponse>()
        val seenUrls = mutableSetOf<String>()
        
        // FIXED: Use same logic as getMainPage to avoid duplicates
        val selectors = listOf(
            ".listupd article.bs",
            "article.bs .bsx",
            ".bsx",
            ".listupd .bsx",
            "article.bs"
        )
        
        for (selector in selectors) {
            val elements = doc.select(selector)
            if (elements.isNotEmpty()) {
                elements.forEach { element ->
                    val result = element.toSearchResult()
                    if (result != null && !seenUrls.contains(result.url)) {
                        seenUrls.add(result.url)
                        items.add(result)
                    }
                }
                if (items.isNotEmpty()) {
                    break
                }
            }
        }
        
        // Fallback for search results
        if (items.isEmpty()) {
            val searchResults = doc.select("article, .bsx, .search-result, .post")
            searchResults.forEach { element ->
                val result = element.toSearchResult()
                if (result != null && !seenUrls.contains(result.url)) {
                    seenUrls.add(result.url)
                    items.add(result)
                }
            }
        }
        
        return items.distinctBy { it.url }
    }

    // Add filtered search support
    override suspend fun search(
        query: String,
        page: Int,
        filter: FilterList?,
        isPage: Boolean
    ): Pair<List<SearchResponse>, Boolean> {
        return if (filter != null) {
            // Handle filtered search
            val filteredResults = handleFilteredSearch(filter, page)
            Pair(filteredResults, filteredResults.isNotEmpty())
        } else {
            // Handle regular search
            val results = if (page == 1) {
                search(query)
            } else {
                emptyList() // Simple search doesn't support pagination
            }
            Pair(results, results.isNotEmpty())
        }
    }
    
    // Handle filtered search with pagination
    private suspend fun handleFilteredSearch(filter: FilterList, page: Int): List<SearchResponse> {
        val params = buildFilterParams(filter)
        
        // Build URL with filters
        var url = "$mainUrl/anime/"
        if (params.isNotEmpty()) {
            url += "?${params.joinToString("&")}"
            if (page > 1) {
                url += "&page=$page"
            }
        } else if (page > 1) {
            url += "page/$page/"
        }
        
        val doc = app.get(url).document
        val items = mutableListOf<SearchResponse>()
        val seenUrls = mutableSetOf<String>()
        
        // Extract anime items
        val selectors = listOf(".listupd article.bs", "article.bs .bsx", ".bsx")
        
        for (selector in selectors) {
            val elements = doc.select(selector)
            if (elements.isNotEmpty()) {
                elements.forEach { element ->
                    val result = element.toSearchResult()
                    if (result != null && !seenUrls.contains(result.url)) {
                        seenUrls.add(result.url)
                        items.add(result)
                    }
                }
                if (items.isNotEmpty()) break
            }
        }
        
        return items
    }
    
    // Build filter parameters from FilterList
    private fun buildFilterParams(filter: FilterList): List<String> {
        val params = mutableListOf<String>()
        
        filter.forEach { filterItem ->
            when (filterItem) {
                is Filter.CheckGroup -> {
                    if (filterItem.name == "Genres" || filterItem.name == "Seasons") {
                        val selected = filterItem.state.filter { it.isChecked }.map { it.value }
                        if (selected.isNotEmpty()) {
                            val paramName = if (filterItem.name == "Genres") "genre[]" else "season[]"
                            selected.forEach { value ->
                                params.add("$paramName=$value")
                            }
                        }
                    }
                }
                is Filter.Select -> {
                    val value = filterItem.state?.value
                    if (!value.isNullOrBlank()) {
                        when (filterItem.name) {
                            "Status" -> params.add("status=$value")
                            "Type" -> params.add("type=$value")
                            "Order" -> params.add("order=$value")
                        }
                    }
                }
                else -> {}
            }
        }
        
        return params
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        
        // Title
        val title = doc.selectFirst("h1.entry-title")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: "Unknown"
        
        // Remove suffix from title
        val cleanTitle = title.replace(" - Anime Slayer Web | موقع انمي سلاير ويب", "")
            .replace("Anime Slayer Web | موقع انمي سلاير ويب", "")
            .trim()
        
        // Poster
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")?.let { fixUrl(it) }
            ?: doc.selectFirst(".thumb img, .thumbook img")?.attr("src")?.let { fixUrl(it) }
        
        // Plot
        val plot = doc.selectFirst(".desc, .entry-content")?.text()?.trim()
            ?: doc.selectFirst("meta[name=description]")?.attr("content")?.trim()
        
        // Check if it's a movie
        val typeClass = doc.selectFirst(".typez")?.text()?.lowercase() 
            ?: doc.selectFirst(".typez")?.attr("class")?.lowercase()
        
        val isMovie = when {
            typeClass?.contains("movie") == true -> true
            else -> {
                val episodeCount = getEpisodeCount(doc)
                episodeCount == 1 || episodeCount == 0
            }
        }
        
        // Extract episodes
        val episodes = extractEpisodes(doc, url)
        
        // Sort episodes by number
        episodes.sortBy { it.episode }
        
        // Get year
        val year = doc.selectFirst("span.split:contains(تم الإصدار:)")?.text()
            ?.filter { it.isDigit() }?.take(4)?.toIntOrNull()
        
        // Get tags
        val tags = doc.select(".genxed a").map { it.text().trim() }
        
        // Return appropriate response
        return if (isMovie || episodes.isEmpty()) {
            newMovieLoadResponse(cleanTitle, url, TvType.AnimeMovie, url) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
            }
        } else {
            newTvSeriesLoadResponse(cleanTitle, url, TvType.Anime, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
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
            val url = data.substringBefore("?ep=")
            val episode = data.substringAfter("?ep=").toIntOrNull() ?: 1
            
            val doc = app.get(url).document
            var foundAnyLink = false
            
            // Get ALL server containers from BOTH noscript and regular page
            val allServers = mutableListOf<Pair<String, String>>() // (serverType, dataValue)
            
            // 1. Extract from noscript element
            val noscriptElement = doc.selectFirst("noscript#diplayer")
            if (noscriptElement != null) {
                val noscriptHtml = noscriptElement.html()
                val noscriptDoc = org.jsoup.Jsoup.parse(noscriptHtml)
                allServers.addAll(extractAllServers(noscriptDoc, episode))
            }
            
            // 2. Extract from regular page
            allServers.addAll(extractAllServers(doc, episode))
            
            // 3. Extract from download links section
            val downloadLinks = doc.select(".linkul li a, .download-links a, a[href*='drive.google.com'], a[href*='mega.nz'], a[href*='4shared.com'], a[href*='vanfem'], a[href*='mp4upload']")
            downloadLinks.forEach { linkElement ->
                val href = linkElement.attr("href")?.trim()
                if (!href.isNullOrBlank()) {
                    val serverType = when {
                        href.contains("drive.google.com") -> "drive"
                        href.contains("mega.nz") -> "mega"
                        href.contains("4shared.com") -> "4shared"
                        href.contains("vanfem") -> "vanfem"
                        href.contains("mp4upload") -> "mp4upload"
                        else -> "direct"
                    }
                    allServers.add((serverType to href))
                }
            }
            
            // Process ALL extracted servers
            allServers.forEach { (serverType, dataValue) ->
                val extractedUrl = generateUrlFromServerData(serverType, dataValue)
                if (extractedUrl != null) {
                    if (loadExtractor(extractedUrl, "$mainUrl/", subtitleCallback, callback)) {
                        foundAnyLink = true
                    }
                }
            }
            
            // Return true if we found ANY links at all
            foundAnyLink
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
    
    // Main function to extract all servers using multiple methods
    private fun extractAllServers(doc: org.jsoup.nodes.Document, episode: Int): List<Pair<String, String>> {
        val servers = mutableListOf<Pair<String, String>>()
        
        // Method 1: Extract from server containers
        servers.addAll(extractFromServerContainers(doc, episode))
        
        return servers.distinct()
    }
    
    // Method 1: Extract from server containers
    private fun extractFromServerContainers(doc: org.jsoup.nodes.Document, episode: Int): List<Pair<String, String>> {
        val servers = mutableListOf<Pair<String, String>>()
        
        // Find all server containers
        val containers = doc.select(".divv11")
        if (containers.isNotEmpty()) {
            // Get the correct container for the episode (episode - 1 for zero-based index)
            val containerIndex = episode - 1
            if (containerIndex < containers.size) {
                val container = containers[containerIndex]
                
                // Extract all li elements from this container
                val serverItems = container.select("li")
                
                serverItems.forEach { item ->
                    val dataValue = item.attr("data")?.trim()
                    val typeValue = item.attr("type")?.trim()?.lowercase()
                    val classValue = item.attr("class")?.trim()?.lowercase()
                    
                    // Determine server type from multiple sources
                    val serverType = when {
                        !typeValue.isNullOrBlank() -> typeValue
                        !classValue.isNullOrBlank() -> {
                            // Extract main class name (remove extra classes)
                            classValue.split(" ").firstOrNull() ?: classValue
                        }
                        else -> item.text().trim().lowercase()
                    }
                    
                    if (!dataValue.isNullOrBlank() && serverType.isNotBlank()) {
                        // Clean server type
                        val cleanType = serverType.replace("videoselect", "")
                            .replace("server", "")
                            .trim()
                        
                        servers.add(cleanType to dataValue)
                    }
                }
            }
        }
        
        return servers
    }
    
    // Smart function to generate URLs from server data
    private fun generateUrlFromServerData(serverType: String, dataValue: String): String? {
        // Clean server type for better matching
        val cleanType = serverType.lowercase()
            .replace(Regex("[^a-z0-9]"), "") // Remove non-alphanumeric
        
        return when {
            // Direct URLs already
            dataValue.startsWith("http") -> dataValue
            
            // MEGA - Check for # symbol in data (fileID#decryptionKey)
            cleanType == "mega" || dataValue.contains("#") -> {
                if (dataValue.contains("#")) {
                    val parts = dataValue.split("#")
                    if (parts.size >= 2) {
                        // MEGA format: https://mega.nz/file/FILEID#DECRYPTIONKEY
                        val fileId = parts[0]
                        val key = parts[1]
                        "https://mega.nz/file/${fileId}#${key}"
                    } else {
                        "https://mega.nz/file/$dataValue"
                    }
                } else {
                    "https://mega.nz/file/$dataValue"
                }
            }
            
            // Google Drive - Check if it looks like a Drive file ID
            cleanType == "drive" || (dataValue.length in 28..44 && dataValue.matches(Regex("[a-zA-Z0-9_-]+"))) -> {
                // Google Drive URLs
                listOf(
                    "https://drive.google.com/file/d/$dataValue/view",
                    "https://drive.google.com/file/d/$dataValue/preview",
                    "https://drive.google.com/uc?id=$dataValue&export=download",
                    "https://drive.google.com/uc?id=$dataValue"
                ).firstOrNull()
            }
            
            // MP4Upload
            cleanType == "mp4upload" -> 
                "https://mp4upload.com/embed-$dataValue.html"
            
            // VANFEM
            cleanType == "vanfem" -> 
                "https://vanfem.com/embed/$dataValue"
            
            // 4shared
            cleanType == "4shared" || cleanType.contains("shared") -> 
                "https://www.4shared.com/video/$dataValue"
            
            // YOURUPLOAD
            cleanType.contains("yourupload") -> 
                "https://www.yourupload.com/embed/$dataValue"
            
            // OK.ru
            cleanType == "ok" -> 
                if (dataValue.matches(Regex("\\d+"))) "https://ok.ru/video/$dataValue" else null
            
            // VIDEA
            cleanType == "videa" -> 
                "https://videa.hu/player?v=$dataValue"
            
            // MAIL.RU
            cleanType == "mailru" -> 
                "https://my.mail.ru/video/embed/$dataValue"
            
            // UQLoad
            cleanType == "uqload" -> 
                "https://uqload.com/embed-$dataValue.html"
            
            // Dailymotion
            cleanType.contains("dailymotion") || cleanType == "dm" -> 
                "https://www.dailymotion.com/embed/video/$dataValue"
            
            // ASNWISH
            cleanType == "asnwish" -> 
                "https://asnwish.com/e/$dataValue"
            
            // Streamtape
            cleanType == "streamtape" -> 
                "https://streamtape.com/e/$dataValue"
            
            // StreamSB
            cleanType.contains("streamsb") || cleanType == "sbplay" -> 
                "https://streamsb.com/e/$dataValue.html"
            
            // MixDrop
            cleanType == "mixdrop" -> 
                "https://mixdrop.co/e/$dataValue"
            
            // Default case
            else -> null
        }
    }
    
    // Helper function to extract episodes
    private fun extractEpisodes(doc: org.jsoup.nodes.Document, baseUrl: String): MutableList<Episode> {
        val episodes = mutableListOf<Episode>()
        
        // First check in the noscript element
        val noscriptElement = doc.selectFirst("noscript#diplayer")
        if (noscriptElement != null) {
            val noscriptHtml = noscriptElement.html()
            val noscriptDoc = org.jsoup.Jsoup.parse(noscriptHtml)
            
            val episodeElements = noscriptDoc.select("#EpList1 .CSB, .CSB, [data-episode]")
            if (episodeElements.isNotEmpty()) {
                episodeElements.forEachIndexed { index, element ->
                    val episodeNum = element.attr("data-episode").toIntOrNull() ?: (index + 1)
                    val episodeName = element.text().trim()
                    
                    episodes.add(
                        newEpisode("$baseUrl?ep=$episodeNum") {
                            this.name = episodeName
                            this.episode = episodeNum
                            this.season = 1
                        }
                    )
                }
            }
        }
        
        // If no episodes found in noscript, try regular way
        if (episodes.isEmpty()) {
            val episodeElements = doc.select("#EpList1 .CSB, .CSB, [data-episode], .eplister li")
            if (episodeElements.isNotEmpty()) {
                episodeElements.forEachIndexed { index, element ->
                    val episodeNum = element.attr("data-episode").toIntOrNull() 
                        ?: element.selectFirst(".eph-num, .epnum")?.text()?.filter { it.isDigit() }?.toIntOrNull()
                        ?: (index + 1)
                    
                    val episodeName = element.text().trim()
                        .replace(Regex("\\d+"), "")
                        .trim()
                    
                    if (episodeName.isNotBlank()) {
                        episodes.add(
                            newEpisode("$baseUrl?ep=$episodeNum") {
                                this.name = episodeName
                                this.episode = episodeNum
                                this.season = 1
                            }
                        )
                    }
                }
            }
        }
        
        return episodes
    }
    
    // Helper function to get episode count
    private fun getEpisodeCount(doc: org.jsoup.nodes.Document): Int {
        // Try multiple methods to get episode count
        val counts = mutableListOf<Int>()
        
        // 1. Count from episode list
        val episodeElements = doc.select("#EpList1 .CSB, .CSB, [data-episode]")
        if (episodeElements.isNotEmpty()) {
            counts.add(episodeElements.size)
        }
        
        // 2. Count from eplister
        val eplisterItems = doc.select(".eplister ul li, .eplister li")
        if (eplisterItems.isNotEmpty()) {
            counts.add(eplisterItems.size)
        }
        
        // 3. Parse from text
        val episodeInfo = doc.select("span:contains(الحلقات:), b:contains(الحلقات:), span:contains(عدد الحلقات:)").firstOrNull()
        val epCountText = episodeInfo?.text() ?: ""
        val epCount = Regex("""\d+""").find(epCountText)?.value?.toIntOrNull()
        if (epCount != null) {
            counts.add(epCount)
        }
        
        // Return the maximum count found, or 0 if none
        return counts.maxOrNull() ?: 0
    }
    
    private fun fixUrl(url: String): String {
        if (url.isBlank()) return ""
        
        return when {
            url.startsWith("http") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "$mainUrl$url"
            else -> "$mainUrl/$url"
        }.replace(Regex("\\?resize=\\d+,\\d+"), "")
    }
}
