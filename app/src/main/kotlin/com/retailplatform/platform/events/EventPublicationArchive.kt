package com.retailplatform.platform.events

import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.ZoneOffset

/**
 * Moves completed event publications out of the registry into `event_publication_archive` once they are old
 * enough, 30 days unless configured otherwise. Runs nightly.
 *
 * Each batch is one statement that deletes the rows and inserts what it deleted, so a publication is never in both
 * tables or in neither. Rows another instance is already moving are skipped, so several instances may run this at
 * once.
 */
@Component
class EventPublicationArchive(
    private val jdbc: JdbcClient,
    private val clock: Clock,
    private val properties: EventPublicationProperties,
) {
    @Scheduled(cron = "\${retail.events.archive.cron:0 30 2 * * *}")
    fun run() {
        val archived = archive()
        if (archived > 0) log.info("Archived {} completed event publications", archived)
    }

    /** @return how many publications were moved to the archive. */
    fun archive(): Int {
        val completedBefore = clock.instant().minus(properties.archive.completedAfter).atOffset(ZoneOffset.UTC)
        var total = 0
        do {
            val moved =
                jdbc
                    .sql(MOVE_BATCH)
                    .param("completedBefore", completedBefore)
                    .param("batchSize", properties.archive.batchSize)
                    .update()
            total += moved
        } while (moved == properties.archive.batchSize)
        return total
    }

    private companion object {
        val log = LoggerFactory.getLogger(EventPublicationArchive::class.java)

        const val COLUMNS =
            "id, listener_id, event_type, serialized_event, publication_date, completion_date, status, " +
                "completion_attempts, last_resubmission_date"

        const val MOVE_BATCH = """
            WITH moved AS (
                DELETE FROM platform.event_publication
                WHERE id IN (
                    SELECT id
                    FROM platform.event_publication
                    WHERE completion_date < :completedBefore
                      AND (status = 'COMPLETED' OR status IS NULL)
                    ORDER BY completion_date
                    LIMIT :batchSize
                    FOR UPDATE SKIP LOCKED
                )
                RETURNING $COLUMNS
            )
            INSERT INTO platform.event_publication_archive ($COLUMNS)
            SELECT $COLUMNS FROM moved
        """
    }
}
