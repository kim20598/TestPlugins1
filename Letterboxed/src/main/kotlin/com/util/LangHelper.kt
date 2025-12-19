package com.letterboxed.util

object LangHelper {
    fun choose(ar: String?, en: String?): String {
        return ar ?: en ?: ""
    }
}
