package com.retailplatform.platform.events

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.util.concurrent.atomic.AtomicReference

/**
 * Watches the event publication registry, every minute unless configured otherwise, so a listener that keeps
 * failing is seen rather than found later in the books.
 *
 * Publishes three gauges for alert rules to use:
 * - `retail.events.publications.incomplete`: publications whose listener has not completed;
 * - `retail.events.publications.failed`: those of them that failed and await a retry;
 * - `retail.events.publications.oldest.incomplete.age`: the age in seconds of the oldest incomplete publication.
 *
 * It also logs an error while any publication is overdue, that is incomplete for longer than
 * `retail.events.monitor.overdue-after`, and names the listeners concerned.
 */
@Component
class EventPublicationMonitor(
    private val jdbc: JdbcClient,
    private val clock: Clock,
    private val properties: EventPublicationProperties,
    meters: MeterRegistry,
) {
    private val latest = AtomicReference(EventPublicationHealth.HEALTHY)

    init {
        Gauge
            .builder("retail.events.publications.incomplete") { latest.get().incomplete }
            .description("Event publications whose listener has not completed")
            .register(meters)
        Gauge
            .builder("retail.events.publications.failed") { latest.get().failed }
            .description("Event publications that failed and await a retry")
            .register(meters)
        Gauge
            .builder("retail.events.publications.oldest.incomplete.age") { latest.get().oldestIncompleteAge.seconds }
            .description("Age of the oldest incomplete event publication")
            .baseUnit("seconds")
            .register(meters)
    }

    @Scheduled(
        initialDelayString = "\${retail.events.monitor.interval:1m}",
        fixedDelayString = "\${retail.events.monitor.interval:1m}",
    )
    fun run() {
        val health = check()
        if (health.overdueListeners.isNotEmpty()) {
            log.error(
                "{} event publications are incomplete, {} of them failed; the oldest was published {} ago. " +
                    "Overdue listeners: {}",
                health.incomplete,
                health.failed,
                health.oldestIncompleteAge,
                health.overdueListeners,
            )
        }
    }

    /** Reads the registry, updates the gauges, and returns what it found. */
    fun check(): EventPublicationHealth {
        val now = clock.instant()
        val overdueBefore = now.minus(properties.monitor.overdueAfter)
        val incomplete =
            jdbc
                .sql(
                    """
                    SELECT listener_id, status, publication_date
                    FROM platform.event_publication
                    WHERE completion_date IS NULL
                    """.trimIndent(),
                ).query { row, _ ->
                    IncompletePublication(
                        listener = row.getString("listener_id"),
                        failed = row.getString("status") == "FAILED",
                        publishedAt = row.getObject("publication_date", OffsetDateTime::class.java).toInstant(),
                    )
                }.list()
        val health =
            EventPublicationHealth(
                incomplete = incomplete.size,
                failed = incomplete.count { it.failed },
                oldestIncompleteAge =
                    incomplete.minOfOrNull { it.publishedAt }?.let { Duration.between(it, now) } ?: Duration.ZERO,
                overdueListeners =
                    incomplete
                        .filter { it.publishedAt < overdueBefore }
                        .map { it.listener }
                        .toSortedSet(),
            )
        latest.set(health)
        return health
    }

    private data class IncompletePublication(
        val listener: String,
        val failed: Boolean,
        val publishedAt: Instant,
    )

    private companion object {
        val log = LoggerFactory.getLogger(EventPublicationMonitor::class.java)
    }
}

/** The state of the event publication registry at one moment. */
data class EventPublicationHealth(
    val incomplete: Int,
    val failed: Int,
    val oldestIncompleteAge: Duration,
    /** The listeners with a publication that has been incomplete for too long. */
    val overdueListeners: Set<String>,
) {
    companion object {
        val HEALTHY = EventPublicationHealth(0, 0, Duration.ZERO, emptySet())
    }
}
