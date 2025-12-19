package com.letterboxed.model

import com.lagradost.cloudstream3.Episode

data class MetaEpisode(
    val season: Int,
    val episodes: List<Episode>
)
