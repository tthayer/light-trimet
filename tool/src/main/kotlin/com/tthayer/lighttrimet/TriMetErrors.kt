package com.tthayer.lighttrimet

const val NETWORK_ERROR_MESSAGE =
    "TriMet is unreachable. Check your network connection and try again."

/** Turns a failure into a message. TriMet's own error text is shown verbatim; everything else is generic. */
fun networkErrorMessage(error: Throwable): String {
    val message = error.message.orEmpty()
    return when {
        message.startsWith("Location") || message.startsWith("TriMet") -> message
        else -> NETWORK_ERROR_MESSAGE
    }
}
