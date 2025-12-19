package com.letterboxed.delegation

import com.letterboxed.model.MetaItem
import com.letterboxed.util.TitleMatcher
import com.letterboxed.util.safe
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class CinebyDelegate : DelegateSource() {

    private val mainUrl = "https://www.cineby.gd"

    override suspend fun searchAndLoad(
        meta: MetaItem,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val searchUrl = "$mainUrl/?s=${meta.title}"

        val doc = safe {
            app.get(searchUrl).document
        } ?: return

        val target = TitleMatcher.normalize(meta.title)

        val resultUrl = doc.select("article a")
            .firstOrNull {
                val title = it.attr("title")
                TitleMatcher.normalize(title).contains(target)
            }
            ?.attr("href")
            ?: return

        loadLinksFromPage(resultUrl, callback)
    }

    private suspend fun loadLinksFromPage(
        url: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val doc = safe {
            app.get(url).document
        } ?: return

        // أغلب مواقع Cineby تعتمد iframe
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
