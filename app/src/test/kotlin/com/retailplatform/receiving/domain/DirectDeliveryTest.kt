package com.retailplatform.receiving.domain

import com.retailplatform.receiving.api.events.DirectDeliveryCorrected
import com.retailplatform.receiving.api.events.DirectDeliveryReceived
import com.retailplatform.shared.BusinessDate
import com.retailplatform.shared.ItemId
import com.retailplatform.shared.Money
import com.retailplatform.shared.StoreId
import com.retailplatform.shared.VendorId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class DirectDeliveryTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-13T15:00:00Z"), ZoneOffset.UTC)
    private val today = BusinessDate.today(clock)
    private val cola = ItemId.generate(clock)
    private val chips = ItemId.generate(clock)
    private val ticket =
        DeliveryTicket(VendorId.generate(clock), StoreId.generate(clock), DeliveryTicketNumber.of(" T-1001 "))
    private val header = ReceiptHeader(ReceiptNumber.of("100001"), "manager")
    private val costs = mapOf(cola to usd("1.25"), chips to usd("0.80"))

    @Test
    fun `a delivery costs each item at the agreed cost and announces what came and went`() {
        val delivery =
            DirectDelivery.receive(
                header,
                ticket,
                listOf(TicketLine.of(cola, 20, 3), TicketLine.of(chips, 0, 5)),
                costs,
                clock,
            )

        delivery.ticket.number.value shouldBe "T-1001"
        delivery.receivedOn shouldBe today
        delivery.receivedBy shouldBe "manager"
        delivery.deliveredCost shouldBe usd("25.00")
        delivery.returnedCost shouldBe usd("7.75")
        delivery.lines.map { it.lineNumber } shouldBe listOf(1, 2)
        val event = delivery.releaseEvents().single().shouldBeInstanceOf<DirectDeliveryReceived>()
        event.delivery.deliveryId shouldBe delivery.id
        event.delivery.receiptNumber shouldBe "100001"
        event.businessDate shouldBe today
        event.lines.first().unitCost shouldBe BigDecimal("1.2500")
        event.lines.map { it.unitsReturned } shouldBe listOf(3, 5)
    }

    @Test
    fun `a delivery lists each item once, at an agreed cost, in one currency`() {
        shouldThrow<DeliveryItemRepeated> {
            DirectDelivery.receive(header, ticket, listOf(TicketLine.of(cola, 1), TicketLine.of(cola, 2)), costs, clock)
        }
        shouldThrow<NoAgreedCost> {
            DirectDelivery.receive(header, ticket, listOf(TicketLine.of(ItemId.generate(clock), 1)), costs, clock)
        }
        shouldThrow<DeliveryInMixedCurrencies> {
            DirectDelivery.receive(
                header,
                ticket,
                listOf(TicketLine.of(cola, 1), TicketLine.of(chips, 1)),
                mapOf(cola to usd("1.25"), chips to Money.of("0.80", "CAD")),
                clock,
            )
        }
        shouldThrow<IllegalArgumentException> { DirectDelivery.receive(header, ticket, emptyList(), costs, clock) }
    }

    @Test
    fun `a correction records the change from what is on record, at the ticket's cost`() {
        val delivery = DirectDelivery.receive(header, ticket, listOf(TicketLine.of(cola, 22, 3)), costs, clock)
        delivery.releaseEvents()
        val second = ReceiptHeader(ReceiptNumber.of("100002"), "manager")

        val correction =
            delivery.correct(
                second,
                CorrectionReason.of(" Miscounted "),
                listOf(CorrectedCount.of(cola, 20, 4), CorrectedCount.of(chips, 10)),
                emptyList(),
                // The cost list today says 9.99 for cola, but the ticket already carries 1.25.
                mapOf(cola to usd("9.99"), chips to usd("0.80")),
                clock,
            )

        correction.reason.value shouldBe "Miscounted"
        correction.lines shouldBe
            listOf(CorrectionLine(cola, -2, 1, usd("1.25")), CorrectionLine(chips, 10, 0, usd("0.80")))
        val event = delivery.releaseEvents().single().shouldBeInstanceOf<DirectDeliveryCorrected>()
        event.correctionReceiptNumber shouldBe "100002"
        event.delivery.receiptNumber shouldBe "100001"
        event.lines.map { it.unitsDeliveredChange } shouldBe listOf(-2, 10)
        val current = delivery.currentLines(listOf(correction))
        current.map { Triple(it.lineNumber, it.unitsDelivered, it.unitsReturned) } shouldBe
            listOf(Triple(1, 20, 4), Triple(2, 10, 0))

        // A later correction starts from the corrected counts, and may bring a line down to nothing.
        val later =
            delivery.correct(
                second,
                CorrectionReason.of("No chips"),
                listOf(CorrectedCount.of(chips, 0)),
                listOf(correction),
                costs,
                clock,
            )
        later.lines shouldBe listOf(CorrectionLine(chips, -10, 0, usd("0.80")))
    }

    @Test
    fun `a correction changes something, leaves something, and costs every item it adds`() {
        val delivery = DirectDelivery.receive(header, ticket, listOf(TicketLine.of(cola, 20)), costs, clock)
        val reason = CorrectionReason.of("Recount")

        shouldThrow<CorrectionChangesNothing> {
            delivery.correct(header, reason, listOf(CorrectedCount.of(cola, 20)), emptyList(), costs, clock)
        }
        shouldThrow<CorrectionWouldVoidDelivery> {
            delivery.correct(header, reason, listOf(CorrectedCount.of(cola, 0)), emptyList(), costs, clock)
        }
        shouldThrow<NoAgreedCost> {
            delivery.correct(
                header,
                reason,
                listOf(CorrectedCount.of(ItemId.generate(clock), 5)),
                emptyList(),
                costs,
                clock,
            )
        }
        shouldThrow<DeliveryItemRepeated> {
            delivery.correct(
                header,
                reason,
                listOf(CorrectedCount.of(cola, 1), CorrectedCount.of(cola, 2)),
                emptyList(),
                costs,
                clock,
            )
        }
        shouldThrow<DeliveryInMixedCurrencies> {
            delivery.correct(
                header,
                reason,
                listOf(CorrectedCount.of(chips, 1)),
                emptyList(),
                mapOf(chips to Money.of("0.80", "CAD")),
                clock,
            )
        }
        shouldThrow<IllegalArgumentException> { CorrectedCount.of(cola, -1) }
        shouldThrow<IllegalArgumentException> { CorrectedCount.of(cola, 1, -1) }
        shouldThrow<IllegalArgumentException> { CorrectionReason.of(" ") }
        shouldThrow<IllegalArgumentException> { CorrectionReason.of("x".repeat(201)) }
    }

    @Test
    fun `ticket lines, ticket numbers, and costs refuse values that make no sense`() {
        shouldThrow<IllegalArgumentException> { TicketLine.of(cola, 0, 0) }
        shouldThrow<IllegalArgumentException> { TicketLine.of(cola, -1, 2) }
        shouldThrow<IllegalArgumentException> { TicketLine.of(cola, 1, -1) }
        shouldThrow<IllegalArgumentException> { DeliveryTicketNumber.of("  ") }
        shouldThrow<IllegalArgumentException> { DeliveryTicketNumber.of("x".repeat(31)) }
        shouldThrow<IllegalArgumentException> {
            AgreedCost.of(ticket.vendorId, cola, ticket.storeId, usd("-0.01"), today)
        }
    }

    private fun usd(amount: String) = Money.of(amount, "USD")
}
