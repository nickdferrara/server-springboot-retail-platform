package com.retailplatform.shared

/**
 * Base for aggregates. An aggregate records the events its methods raise; the use case that called it publishes
 * them after saving, in the same transaction.
 */
abstract class AggregateRoot {
    private val pendingEvents = mutableListOf<Any>()

    protected fun raise(event: Any) {
        pendingEvents += event
    }

    /** Hands over the events raised since the aggregate was loaded or created, so each is published once. */
    fun releaseEvents(): List<Any> = pendingEvents.toList().also { pendingEvents.clear() }
}
