package com.retailplatform.platform.idempotency

import com.retailplatform.shared.EventId
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.ZoneOffset

/**
 * Lets an event listener handle each event once, however many times it is delivered.
 *
 * Every module that listens to events has a `processed_event` table in its own schema. A listener records the event
 * in the same transaction as its work, so a failed attempt leaves no record and the retry runs normally.
 */
@Component
class ProcessedEvents(
    private val jdbc: JdbcClient,
    private val clock: Clock,
) {
    /**
     * Runs [action] unless [listener] in [module] has already processed [eventId].
     *
     * Must be called inside the listener's transaction.
     *
     * @return `true` if [action] ran, `false` if the event was a duplicate.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    fun once(
        module: String,
        listener: String,
        eventId: EventId,
        action: () -> Unit,
    ): Boolean {
        require(SCHEMA_NAME.matches(module)) { "Not a module schema name: '$module'" }
        val recorded =
            jdbc
                .sql(
                    """
                    INSERT INTO $module.processed_event (event_id, listener, processed_at)
                    VALUES (:eventId, :listener, :processedAt)
                    ON CONFLICT DO NOTHING
                    """.trimIndent(),
                ).param("eventId", eventId.value)
                .param("listener", listener)
                .param("processedAt", clock.instant().atOffset(ZoneOffset.UTC))
                .update()
        if (recorded == 1) action()
        return recorded == 1
    }

    private companion object {
        val SCHEMA_NAME = Regex("[a-z][a-z0-9_]*")
    }
}
