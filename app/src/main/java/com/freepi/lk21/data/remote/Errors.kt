package com.freepi.lk21.data.remote

/** Base error for anything that goes wrong while talking to the data source. */
open class SourceException(message: String, cause: Throwable? = null) : Exception(message, cause)

class NetworkUnavailableException(message: String = "No internet connection", cause: Throwable? = null) :
    SourceException(message, cause)

class AccessBlockedException(
    message: String = "The data source is protected by an anti-bot challenge. Try again in a moment.",
    cause: Throwable? = null,
) : SourceException(message, cause)

class ParseException(message: String, cause: Throwable? = null) : SourceException(message, cause)

class StreamResolutionException(message: String, cause: Throwable? = null) : SourceException(message, cause)
