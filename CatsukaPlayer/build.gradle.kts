// use an integer for version numbers
version = 1

cloudstream {
    language = "en"
    // All of these properties are optional, you can safely remove them

    description = "Animation works from Catsuka Player - Independent animation showcase"
    authors = listOf("kim20598")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 1 // will be 3 if unspecified
    tvTypes = listOf(
        "AnimeMovie",
        "Anime",
        "Cartoon",
        "OVA"
    )

    // Catsuka logo or generic animation icon
    iconUrl = "https://www.catsuka.com/favicon.ico"

    isCrossPlatform = true
}