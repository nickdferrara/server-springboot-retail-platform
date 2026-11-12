package com.retailplatform.platform.events

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * How the event publication registry is kept healthy, under `retail.events`.
 *
 * When a publication counts as stale is configured separately, under `spring.modulith.events.staleness`.
 */
@ConfigurationProperties("retail.events")
data class EventPublicationProperties(
    val retry: Retry = Retry(),
    val monitor: Monitor = Monitor(),
    val archive: Archive = Archive(),
) {
    data class Retry(
        /** A failed publication is retried once it was published at least this long ago. */
        val minAge: Duration = Duration.ofMinutes(1),
        /** The most publications one run retries, and the most that may be awaiting their listener at once. */
        val batchSize: Int = 100,
    )

    data class Monitor(
        /** An incomplete publication older than this is overdue and reported. */
        val overdueAfter: Duration = Duration.ofMinutes(OVERDUE_MINUTES),
    )

    data class Archive(
        /** A completed publication moves to the archive this long after it completed. */
        val completedAfter: Duration = Duration.ofDays(ARCHIVE_DAYS),
        /** The most publications one statement moves. */
        val batchSize: Int = 1000,
    )

    private companion object {
        const val OVERDUE_MINUTES = 15L
        const val ARCHIVE_DAYS = 30L
    }
}
