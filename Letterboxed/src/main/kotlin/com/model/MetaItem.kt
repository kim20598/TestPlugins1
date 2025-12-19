package com.letterboxed.model

import com.lagradost.cloudstream3.TvType

data class MetaItem(
    val id: String,
    val title: String,
    val originalTitle: String?,
    val poster: String?,
    val plot: String?,
    val year: Int?,
    val type: TvType
)
