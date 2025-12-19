package com.letterboxed.util

inline fun <T> safe(block: () -> T?): T? {
    return try {
        block()
    } catch (e: Throwable) {
        null
    }
}
