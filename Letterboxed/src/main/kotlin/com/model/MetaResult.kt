package com.letterboxed.model

data class MetaResult(
    val item: MetaItem,
    val episodes: List<MetaEpisode> = emptyList()
)
