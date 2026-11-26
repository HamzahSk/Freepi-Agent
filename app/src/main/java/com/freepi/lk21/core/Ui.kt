package com.freepi.lk21.core

import com.freepi.lk21.data.remote.AccessBlockedException
import com.freepi.lk21.data.remote.NetworkUnavailableException
import com.freepi.lk21.data.remote.SourceException

fun Throwable.userMessage(): String = when (this) {
    is AccessBlockedException -> message ?: "The data source is temporarily blocking requests."
    is NetworkUnavailableException -> message ?: "Network unavailable."
    is SourceException -> message ?: "The data source could not be read."
    else -> message ?: "An unexpected error occurred."
}
