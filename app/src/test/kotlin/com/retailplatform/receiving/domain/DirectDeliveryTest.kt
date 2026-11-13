package com.retailplatform.receiving.domain

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
