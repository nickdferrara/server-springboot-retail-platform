package com.retailplatform.platform.events

import com.retailplatform.TestcontainersConfiguration
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.MeterRegistry
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** An event only these tests publish. */
data class DeliveryAttempted(
    val id: UUID,
)

/** A listener that fails as many times as a test tells it to before it completes. */
@Component
class FlakyListener {
    private val failuresLeft = ConcurrentHashMap<UUID, AtomicInteger>()
    private val deliveries = ConcurrentHashMap<UUID, AtomicInteger>()

    fun failTimes(
        id: UUID,
        times: Int,
    ) {
        failuresLeft[id] = AtomicInteger(times)
    }

    fun deliveries(id: UUID): Int = deliveries[id]?.get() ?: 0

    @ApplicationModuleListener
    fun on(event: DeliveryAttempted) {
        deliveries.computeIfAbsent(event.id) { AtomicInteger() }.incrementAndGet()
        val left = failuresLeft[event.id]
        if (left != null && left.getAndDecrement() > 0) error("The listener failed on purpose")
    }
}

@SpringBootTest(
    properties = [
        "retail.events.retry.interval=500ms",
        "retail.events.retry.min-age=0s",
        "spring.modulith.events.staleness.check-interval=1s",
        // The tests run the monitor themselves, so its gauges do not change under them.
        "retail.events.monitor.interval=1h",
    ],
)
@Import(TestcontainersConfiguration::class, FlakyListener::class)
class EventPublicationMaintenanceTest(
    @Autowired private val events: ApplicationEventPublisher,
    @Autowired private val transaction: TransactionTemplate,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val listener: FlakyListener,
    @Autowired private val monitor: EventPublicationMonitor,
    @Autowired private val archive: EventPublicationArchive,
    @Autowired private val meters: MeterRegistry,
) {
    private val patience = Duration.ofSeconds(30)

    @Test
    fun `a publication whose listener fails is retried until the listener completes`() {
        val id = UUID.randomUUID()
        listener.failTimes(id, 2)

        publish(id)

        await().atMost(patience).untilAsserted { status(id) shouldBe "COMPLETED" }
        listener.deliveries(id) shouldBe 3
        publication(id)["completion_attempts"] as Int shouldBeGreaterThanOrEqual 2
    }

    @Test
    fun `a publication stuck in processing goes stale, is marked failed, and is retried`() {
        val id = UUID.randomUUID()
        publish(id)
        await().atMost(patience).untilAsserted { status(id) shouldBe "COMPLETED" }

        // As if the instance running the listener stopped half way, an hour ago.
        jdbc
            .sql(
                """
                UPDATE platform.event_publication
                SET status = 'PROCESSING',
                    completion_date = NULL,
                    publication_date = now() - INTERVAL '1 hour',
                    last_resubmission_date = now() - INTERVAL '1 hour'
                WHERE serialized_event LIKE :event
                """.trimIndent(),
            ).param("event", "%$id%")
            .update() shouldBe 1

        await().atMost(patience).untilAsserted {
            status(id) shouldBe "COMPLETED"
            listener.deliveries(id) shouldBe 2
        }
    }

    @Test
    fun `failed and overdue publications are counted, exposed as gauges, and named by listener`() {
        val id = UUID.randomUUID()
        listener.failTimes(id, Int.MAX_VALUE)
        publish(id)
        await().atMost(patience).untilAsserted { listener.deliveries(id) shouldBeGreaterThanOrEqual 1 }
        // Published long enough ago to be overdue.
        jdbc
            .sql(
                """
                UPDATE platform.event_publication SET publication_date = now() - INTERVAL '20 minutes'
                WHERE serialized_event LIKE :event
                """.trimIndent(),
            ).param("event", "%$id%")
            .update() shouldBe 1

        val health = monitor.check()

        health.incomplete shouldBeGreaterThanOrEqual 1
        health.oldestIncompleteAge.toMinutes().toInt() shouldBeGreaterThanOrEqual 20
        health.overdueListeners.single { "FlakyListener" in it }
        meters
            .get("retail.events.publications.incomplete")
            .gauge()
            .value()
            .toInt() shouldBe health.incomplete
        meters
            .get("retail.events.publications.failed")
            .gauge()
            .value()
            .toInt() shouldBe health.failed
        meters
            .get("retail.events.publications.oldest.incomplete.age")
            .gauge()
            .value()
            .toLong() shouldBe health.oldestIncompleteAge.seconds

        // Once the listener recovers, the publication completes and is no longer reported.
        listener.failTimes(id, 0)
        await().atMost(patience).untilAsserted { status(id) shouldBe "COMPLETED" }
        monitor.check().overdueListeners.none { "FlakyListener" in it } shouldBe true
    }

    @Test
    fun `completed publications move to the archive after thirty days`() {
        val old = UUID.randomUUID()
        val recent = UUID.randomUUID()
        val failing = UUID.randomUUID()
        listener.failTimes(failing, Int.MAX_VALUE)
        listOf(old, recent, failing).forEach(::publish)
        await().atMost(patience).untilAsserted {
            status(old) shouldBe "COMPLETED"
            status(recent) shouldBe "COMPLETED"
        }
        completedDaysAgo(old, 31)
        completedDaysAgo(recent, 29)
        // An incomplete publication stays in the registry however old it is.
        jdbc
            .sql(
                """
                UPDATE platform.event_publication SET publication_date = now() - INTERVAL '90 days'
                WHERE serialized_event LIKE :event
                """.trimIndent(),
            ).param("event", "%$failing%")
            .update()

        archive.archive() shouldBeGreaterThanOrEqual 1

        tablesHolding(old) shouldBe listOf("event_publication_archive")
        tablesHolding(recent) shouldBe listOf("event_publication")
        tablesHolding(failing) shouldBe listOf("event_publication")
        archive.archive() shouldBe 0

        listener.failTimes(failing, 0)
        await().atMost(patience).untilAsserted { status(failing) shouldBe "COMPLETED" }
    }

    private fun publish(id: UUID) {
        transaction.executeWithoutResult { events.publishEvent(DeliveryAttempted(id)) }
    }

    private fun status(id: UUID): String? = publication(id)["status"] as String?

    private fun publication(id: UUID): Map<String, Any?> =
        jdbc
            .sql("SELECT * FROM platform.event_publication WHERE serialized_event LIKE :event")
            .param("event", "%$id%")
            .query()
            .singleRow()

    private fun completedDaysAgo(
        id: UUID,
        days: Int,
    ) {
        jdbc
            .sql(
                """
                UPDATE platform.event_publication SET completion_date = now() - make_interval(days => :days)
                WHERE serialized_event LIKE :event
                """.trimIndent(),
            ).param("days", days)
            .param("event", "%$id%")
            .update() shouldBe 1
    }

    private fun tablesHolding(id: UUID): List<String> =
        listOf("event_publication", "event_publication_archive").filter { table ->
            jdbc
                .sql("SELECT count(*) FROM platform.$table WHERE serialized_event LIKE :event")
                .param("event", "%$id%")
                .query(Long::class.java)
                .single() == 1L
        }
}
