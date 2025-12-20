// use an integer for version numbers
version = 1

cloudstream {
    language = "en"
    authors = listOf("kim20598") // Replace with your name
    description = "Catsuka Player - A free video platform for animation lovers. Explore short films, pilots, episodes, and more."
    
    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     */
    status = 1
    
    tvTypes = listOf(
        "Movie",
        "TvSeries",
        "Anime",
        "AnimeMovie",
        "OVA",
        "Cartoon"
    )
    
    iconUrl = "https://t2.gstatic.com/faviconV2?client=SOCIAL&type=FAVICON&fallback_opts=TYPE,SIZE,URL&url=https://www.catsuka.com&size=256"
    
    isCrossPlatform = true
}