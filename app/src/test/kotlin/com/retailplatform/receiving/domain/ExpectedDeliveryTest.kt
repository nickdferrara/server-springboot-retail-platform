package com.retailplatform.receiving.domain

import com.retailplatform.receiving.api.events.GoodsReceived
import com.retailplatform.receiving.api.events.ReceiptDiscrepancyRecorded
import com.retailplatform.shared.BusinessDate
import com.retailplatform.shared.ItemId
import com.retailplatform.shared.Money
import com.retailplatform.shared.PurchaseOrderId
import com.retailplatform.shared.VendorId
import com.retailplatform.shared.WarehouseId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class ExpectedDeliveryTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-11T15:00:00Z"), ZoneOffset.UTC)
    private val today = BusinessDate.today(clock)
    private val chips = ItemId.generate(clock)
    private val cola = ItemId.generate(clock)
    private val header = ReceiptHeader(ReceiptNumber.of("100001"), "clerk")

    @Test
    fun `a full delivery goes into stock at order cost and completes the order`() {
        val delivery = expected()

        val receipt = delivery.receive(listOf(CountedLine.of(1, 10), CountedLine.of(2, 5)), false, header, clock)

        delivery.status shouldBe DeliveryStatus.COMPLETE
        receipt.receivedBy shouldBe "clerk"
        receipt.receivedOn shouldBe today
        receipt.lines.map { it.extendedCost } shouldBe listOf(usd("180.00"), usd("120.00"))
        val event = delivery.releaseEvents().single().shouldBeInstanceOf<GoodsReceived>()
        event.purchaseOrderComplete shouldBe true
        event.receipt.receiptNumber shouldBe "100001"
        event.lines.map { it.casesReceived } shouldBe listOf(10, 5)
        event.lines.first().caseCost shouldBe BigDecimal("18.0000")
        shouldThrow<DeliveryNotOpen> { delivery.receive(listOf(CountedLine.of(1, 1)), false, header, clock) }
    }

    @Test
    fun `a partial delivery leaves the order open for the rest`() {
        val delivery = expected()

        val first = delivery.receive(listOf(CountedLine.of(1, 4)), false, header, clock)

        delivery.status shouldBe DeliveryStatus.OPEN
        delivery.lines.map { it.casesOutstanding } shouldBe listOf(6, 5)
        first.lines.single().hasDiscrepancy shouldBe false
        delivery
            .releaseEvents()
            .single()
            .shouldBeInstanceOf<GoodsReceived>()
            .purchaseOrderComplete shouldBe false

        delivery.receive(listOf(CountedLine.of(1, 6), CountedLine.of(2, 5)), false, header, clock)
        delivery.status shouldBe DeliveryStatus.COMPLETE
    }

    @Test
    fun `over, damaged, and short cases are recorded as discrepancies`() {
        val delivery = expected()

        val receipt =
            delivery.receive(listOf(CountedLine.of(1, 12, casesDamaged = 1)), finalDelivery = true, header, clock)

        delivery.status shouldBe DeliveryStatus.COMPLETE
        val chipsLine = receipt.lines.first { it.lineNumber == 1 }
        chipsLine.casesOver shouldBe 2
        chipsLine.casesDamaged shouldBe 1
        chipsLine.casesShort shouldBe 0
        val colaLine = receipt.lines.first { it.lineNumber == 2 }
        colaLine.casesReceived shouldBe 0
        colaLine.casesShort shouldBe 5
        val events = delivery.releaseEvents()
        events
            .filterIsInstance<GoodsReceived>()
            .single()
            .lines
            .map { it.itemId } shouldBe listOf(chips)
        val discrepancy = events.filterIsInstance<ReceiptDiscrepancyRecorded>().single()
        discrepancy.lines.map { Triple(it.casesOver, it.casesShort, it.casesDamaged) } shouldBe
            listOf(Triple(2, 0, 1), Triple(0, 5, 0))
    }

    @Test
    fun `damaged cases are refused and stay outstanding`() {
        val delivery = expected()

        delivery.receive(listOf(CountedLine.of(2, 3, casesDamaged = 2)), false, header, clock)

        delivery.lines.first { it.lineNumber == 2 }.casesOutstanding shouldBe 2
        delivery.status shouldBe DeliveryStatus.OPEN
    }

    @Test
    fun `closing an order short with nothing delivered records the shortage and no goods`() {
        val delivery = expected()

        val receipt = delivery.receive(emptyList(), finalDelivery = true, header, clock)

        receipt.lines.map { it.casesShort } shouldBe listOf(10, 5)
        delivery.releaseEvents().single().shouldBeInstanceOf<ReceiptDiscrepancyRecorded>()
    }

    @Test
    fun `a receipt must count lines on the order, each once, and record something`() {
        val delivery = expected()

        shouldThrow<LineNotOnOrder> { delivery.receive(listOf(CountedLine.of(3, 1)), false, header, clock) }
        shouldThrow<LineCountedTwice> {
            delivery.receive(listOf(CountedLine.of(1, 1), CountedLine.of(1, 2)), false, header, clock)
        }
        shouldThrow<ReceiptRecordsNothing> { delivery.receive(listOf(CountedLine.of(1, 0)), false, header, clock) }
        shouldThrow<ReceiptRecordsNothing> { delivery.receive(emptyList(), false, header, clock) }
        shouldThrow<IllegalArgumentException> { CountedLine.of(1, -1) }
        shouldThrow<IllegalArgumentException> { CountedLine.of(0, 1) }
        shouldThrow<IllegalArgumentException> { ReceiptNumber.of("R-1") }
        delivery.releaseEvents().shouldBeEmpty()
    }

    @Test
    fun `a cancelled order is no longer expected, and a completed one stays complete`() {
        val cancelled = expected()
        cancelled.cancel()
        cancelled.status shouldBe DeliveryStatus.CANCELLED
        shouldThrow<DeliveryNotOpen> { cancelled.receive(listOf(CountedLine.of(1, 1)), false, header, clock) }

        val complete = expected()
        complete.receive(emptyList(), finalDelivery = true, header, clock)
        complete.cancel()
        complete.status shouldBe DeliveryStatus.COMPLETE
    }

    private fun expected() =
        ExpectedDelivery.expect(
            ReceivedAgainst(
                PurchaseOrderId.generate(clock),
                "100001",
                VendorId.generate(clock),
                WarehouseId.generate(clock),
            ),
            today,
            listOf(
                ExpectedLine(1, chips, "CH-1", casePack = 12, caseCost = usd("18.00"), casesOrdered = 10),
                ExpectedLine(2, cola, "CO-1", casePack = 24, caseCost = usd("24.00"), casesOrdered = 5),
            ),
        )

    private fun usd(amount: String) = Money.of(amount, "USD")
}
