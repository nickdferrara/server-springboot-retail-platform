package com.retailplatform.pricing

import com.retailplatform.TestcontainersConfiguration
import com.retailplatform.locations.api.events.StoreOpened
import com.retailplatform.pricing.adapter.`in`.events.StoreOpenedListener
import com.retailplatform.pricing.application.StorePriceZoneRepository
import com.retailplatform.pricing.domain.PriceZoneCode
import com.retailplatform.shared.BusinessDate
import com.retailplatform.shared.EventId
import com.retailplatform.shared.StoreId
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.modulith.test.ApplicationModuleTest
import org.springframework.modulith.test.Scenario
import java.time.Clock
import java.time.Duration
import java.util.Optional
import java.util.function.Supplier

@ApplicationModuleTest
@Import(TestcontainersConfiguration::class)
class PricingModuleTest(
    @Autowired private val assignments: StorePriceZoneRepository,
    @Autowired private val listener: StoreOpenedListener,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val clock: Clock,
) {
    @Test
    fun `a store that opens is put in the default price zone`(scenario: Scenario) {
        val event = storeOpened()

        scenario
            .publish(event)
            .andWaitForStateChange(Supplier { Optional.ofNullable(assignments.findByStore(event.storeId)) })
            .andVerify { assignment ->
                assignment.orElseThrow().zone shouldBe PriceZoneCode.DEFAULT
            }
    }

    @Test
    fun `the same event delivered twice is handled once`() {
        val event = storeOpened()

        // The listener is asynchronous, so both deliveries are awaited before counting.
        repeat(2) { listener.on(event) }

        await().atMost(Duration.ofSeconds(10)).untilAsserted {
            assignments.findByStore(event.storeId).shouldNotBeNull()
        }
        await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(5)).untilAsserted {
            processedCount(event) shouldBe 1
        }
    }

    private fun processedCount(event: StoreOpened): Int =
        jdbc
            .sql("SELECT count(*) FROM pricing.processed_event WHERE event_id = :id")
            .param("id", event.eventId.value)
            .query(Int::class.java)
            .single()

    private fun storeOpened() =
        StoreOpened(
            eventId = EventId.generate(clock),
            occurredAt = clock.instant(),
            businessDate = BusinessDate.parse("2026-12-01"),
            storeId = StoreId.generate(clock),
            storeNumber = "0301",
            storeName = "Mill Lane",
            timeZone = "America/Chicago",
        )
}
