package com.retailplatform.shared

/** The thing a command or query refers to does not exist. */
class ResourceNotFound(
    val resource: String,
    val id: String,
) : RuntimeException("$resource $id was not found")
