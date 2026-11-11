package com.retailplatform.platform.web

/** A value in a request was refused by the domain type it was being turned into. Reported as a 400. */
class InvalidRequestValue(
    message: String,
    cause: Throwable,
) : RuntimeException(message, cause)

/**
 * Wraps the step where an inbound adapter turns request values into domain types.
 *
 * A value object that refuses its input throws [IllegalArgumentException]; inside this block that becomes
 * [InvalidRequestValue], so the caller gets a 400. The same exception thrown anywhere else is a bug and stays a 500.
 */
inline fun <T> mapRequest(block: () -> T): T =
    try {
        block()
    } catch (e: IllegalArgumentException) {
        throw InvalidRequestValue(e.message ?: "A value in the request is invalid.", e)
    }
