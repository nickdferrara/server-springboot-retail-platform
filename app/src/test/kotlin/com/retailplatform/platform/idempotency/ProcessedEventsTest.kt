package com.retailplatform.platform.idempotency

import com.retailplatform.TestcontainersConfiguration
import com.retailplatform.shared.EventId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.transaction.IllegalTransactionStateException
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock

@SpringBootTest
@Import(TestcontainersConfiguration::class)
class ProcessedEventsTest(
    @Autowired private val processedEvents: ProcessedEvents,
    @Autowired private val transaction: TransactionTemplate,
    @Autowired private val clock: Clock,
) {
    @Test
    fun `a listener handles an event the first time and skips redeliveries`() {
        val eventId = EventId.generate(clock)
        var handled = 0

        val results = List(3) { deliver(eventId, "first-listener") { handled++ } }

        results shouldBe listOf(true, false, false)
        handled shouldBe 1
    }

    @Test
    fun `two listeners each handle the same event`() {
        val eventId = EventId.generate(clock)
        var handled = 0

        deliver(eventId, "first-listener") { handled++ }
        deliver(eventId, "second-listener") { handled++ }

        handled shouldBe 2
    }

    @Test
    fun `a failed attempt leaves no record, so the retry runs`() {
        val eventId = EventId.generate(clock)

        shouldThrow<IllegalStateException> { deliver(eventId, "first-listener") { error("listener failed") } }

        deliver(eventId, "first-listener") {} shouldBe true
    }

    @Test
    fun `must run inside the listener's transaction`() {
        shouldThrow<IllegalTransactionStateException> {
            processedEvents.once("platform", "first-listener", EventId.generate(clock)) {}
        }
    }

    @Test
    fun `rejects a module name that is not a schema name`() {
        shouldThrow<IllegalArgumentException> { deliver(EventId.generate(clock), "first-listener", "bad; name") {} }
    }

    private fun deliver(
        eventId: EventId,
        listener: String,
        module: String = "platform",
        action: () -> Unit,
    ): Boolean = transaction.execute { processedEvents.once(module, listener, eventId, action) } == true
}
