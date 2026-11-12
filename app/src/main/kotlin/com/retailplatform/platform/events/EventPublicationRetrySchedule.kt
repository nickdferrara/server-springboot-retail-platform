package com.retailplatform.platform.events

import org.springframework.modulith.events.FailedEventPublications
import org.springframework.modulith.events.ResubmissionOptions
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Hands failed event publications back to their listeners, every minute unless configured otherwise.
 *
 * A publication fails when its listener throws, or when the registry finds it stale: published, processing, or
 * resubmitted for longer than `spring.modulith.events.staleness` allows, for example because the instance running
 * the listener stopped. A publication is retried until its listener completes.
 *
 * Several instances may run this at once. The registry claims each publication with a conditional update, so only
 * one instance resubmits it; should a listener still run twice, it records the event in its module's
 * `processed_event` table and does its work once.
 */
@Component
class EventPublicationRetrySchedule(
    private val failedPublications: FailedEventPublications,
    private val properties: EventPublicationProperties,
) {
    @Scheduled(
        initialDelayString = "\${retail.events.retry.interval:1m}",
        fixedDelayString = "\${retail.events.retry.interval:1m}",
    )
    fun run() {
        failedPublications.resubmit(
            ResubmissionOptions
                .defaults()
                .withMinAge(properties.retry.minAge)
                .withBatchSize(properties.retry.batchSize)
                .withMaxInFlight(properties.retry.batchSize),
        )
    }
}
