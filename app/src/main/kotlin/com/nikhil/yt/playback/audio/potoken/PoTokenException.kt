package com.nikhil.yt.playback.audio.potoken

class PoTokenException(message: String, cause: Throwable? = null) : Exception(message, cause)

class BadWebViewException(message: String, cause: Throwable? = null) : Exception(message, cause)

fun buildExceptionForJsError(error: String): Exception =
    if (error.contains("SyntaxError", ignoreCase = true)) {
        BadWebViewException(error)
    } else {
        PoTokenException(error)
    }
