package com.letterboxed.delegation

import com.letterboxed.model.MetaItem
import com.letterboxed.util.TitleMatcher
import com.letterboxed.util.safe
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup

class ArabseedDelegate : DelegateSource() {

    private val mainUrl = "https://arabseed.ink"

    override suspend fun searchAndLoad(
        meta: MetaItem,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val searchUrl = "$mainUrl/?s=${meta.title}"

        val doc = safe {
            app.get(searchUrl).document
        } ?: return

        val normalizedTarget = TitleMatcher.normalize(meta.title)

        val resultUrl = doc.select("div.BlockItem a")
            .firstOrNull {
                val title = it.attr("title")
                TitleMatcher.normalize(title).contains(normalizedTarget)
            }
            ?.attr("href")
            ?: return

        loadMovieLinks(resultUrl, callback)
    }

    private suspend fun loadMovieLinks(
        url: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val doc = safe {
            app.get(url).document
        } ?: return

        doc.select("iframe").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.isNotBlank()) {
                loadExtractor(
                    src,
                    referer = url,
                    callback = callback
                )
            }
        }
    }
}
