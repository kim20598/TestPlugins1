import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.SubtitleFile
import com.lagradost.cloudstream3.utils.loadExtractor

class AkwamDelegate : DelegateSource() {
    override suspend fun searchAndLoad(
        meta: MetaItem,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // Your search and load logic here
        val url = "..." // Example URL
        loadExtractor(url, subtitleCallback, callback) // Correct call
    }
}
