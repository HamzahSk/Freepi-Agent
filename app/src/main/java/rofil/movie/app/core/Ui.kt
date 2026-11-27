package rofil.movie.app.core

import rofil.movie.app.data.remote.AccessBlockedException
import rofil.movie.app.data.remote.NetworkUnavailableException
import rofil.movie.app.data.remote.SourceException

fun Throwable.userMessage(): String = when (this) {
    is AccessBlockedException -> message ?: "The data source is temporarily blocking requests."
    is NetworkUnavailableException -> message ?: "Network unavailable."
    is SourceException -> message ?: "The data source could not be read."
    else -> message ?: "An unexpected error occurred."
}
