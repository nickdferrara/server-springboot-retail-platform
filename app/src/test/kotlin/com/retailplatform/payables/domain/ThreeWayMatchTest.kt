package com.retailplatform.payables.domain

import com.retailplatform.payables.api.events.InvoiceMatchException
import com.retailplatform.payables.api.events.InvoiceMatched
import com.retailplatform.shared.BusinessDate
import com.retailplatform.shared.ItemId
import com.retailplatform.shared.Money
import com.retailplatform.shared.Percentage
import com.retailplatform.shared.PurchaseOrderId
import com.retailplatform.shared.ReceiptId
import com.retailplatform.shared.VendorId
import com.retailplatform.shared.WarehouseId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class ThreeWayMatchTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-12T15:00:00Z"), ZoneOffset.UTC)
    private val today = BusinessDate.today(clock)
    private val vendorId = VendorId.generate(clock)
    private val orderId = PurchaseOrderId.generate(clock)
    private val chips = ItemId.generate(clock)
    private val cola = ItemId.generate(clock)
    private val match = ThreeWayMatch(MatchTolerance.of(Percentage.of("2"), BigDecimal("10.00")))

    @Test
    fun `an invoice for what was received at order cost matches and relieves the accrual`() {
        val accrual = accrual(AccrualLine.of(1, chips, 10, usd("18.00")), AccrualLine.of(2, cola, 5, usd("24.00")))
        val invoice = invoice(InvoiceLine.of(1, 10, usd("18.00")), InvoiceLine.of(2, 5, usd("24.00")))

        val outcome = match.match(invoice, listOf(accrual))
        invoice.record(outcome, clock)

        outcome shouldBe MatchOutcome.Matched(usd("300.00"))
        accrual.isOpen shouldBe false
        invoice.status shouldBe InvoiceStatus.Matched(today)
        val event = invoice.releaseEvents().single().shouldBeInstanceOf<InvoiceMatched>()
        event.invoiceAmount shouldBe BigDecimal("300.0000")
        event.accruedAmount shouldBe BigDecimal("300.0000")
        event.priceVariance shouldBe BigDecimal("0.0000")
        shouldThrow<InvoiceAlreadyMatched> { invoice.record(outcome, clock) }
    }

    @Test
    fun `a partial invoice relieves the oldest receipt first and leaves the rest open`() {
        val older = accrual(AccrualLine.of(1, chips, 4, usd("18.00")), receivedOn = today.minusDays(5))
        val newer = accrual(AccrualLine.of(1, chips, 6, usd("18.00")), receivedOn = today.minusDays(1))

        val outcome = match.match(invoice(InvoiceLine.of(1, 7, usd("18.00"))), listOf(older, newer))

        outcome shouldBe MatchOutcome.Matched(usd("126.00"))
        older.isOpen shouldBe false
        newer.uninvoiced(1) shouldBe 3
        newer.openAmount shouldBe usd("54.00")
    }

    @Test
    fun `a cost within tolerance matches and the difference is a price variance`() {
        val accrual = accrual(AccrualLine.of(1, chips, 10, usd("18.00")))
        val invoice = invoice(InvoiceLine.of(1, 10, usd("18.30")))

        invoice.record(match.match(invoice, listOf(accrual)), clock)

        val event = invoice.releaseEvents().single().shouldBeInstanceOf<InvoiceMatched>()
        event.invoiceAmount shouldBe BigDecimal("183.0000")
        event.accruedAmount shouldBe BigDecimal("180.0000")
        event.priceVariance shouldBe BigDecimal("3.0000")
    }

    @Test
    fun `a line over either tolerance, over receipt, or never received is an exception and relieves nothing`() {
        val accrual = accrual(AccrualLine.of(1, chips, 10, usd("18.00")), AccrualLine.of(2, cola, 100, usd("24.00")))
        val invoice =
            invoice(
                InvoiceLine.of(1, 11, usd("18.00")),
                // 1% a case is within the percentage, but 100 cases at 0.24 is 24.00, over the amount.
                InvoiceLine.of(2, 100, usd("24.24")),
                InvoiceLine.of(3, 1, usd("5.00")),
            )

        val outcome = match.match(invoice, listOf(accrual))
        invoice.record(outcome, clock)

        outcome.shouldBeInstanceOf<MatchOutcome.Exceptions>().exceptions.map { it.reason } shouldBe
            listOf(
                MatchExceptionReason.QUANTITY_OVER_RECEIPT,
                MatchExceptionReason.PRICE_OVER_TOLERANCE,
                MatchExceptionReason.NOTHING_RECEIVED,
            )
        accrual.openAmount shouldBe accrual.amount
        invoice.status
            .shouldBeInstanceOf<InvoiceStatus.Exception>()
            .exceptions.size shouldBe 3
        val event = invoice.releaseEvents().single().shouldBeInstanceOf<InvoiceMatchException>()
        event.exceptions.map { it.purchaseOrderLineNumber } shouldBe listOf(1, 2, 3)
    }

    @Test
    fun `an invoice that failed can match once the goods arrive`() {
        val invoice = invoice(InvoiceLine.of(1, 10, usd("18.00")))
        invoice.record(match.match(invoice, emptyList()), clock)
        invoice.status.code shouldBe "EXCEPTION"

        invoice.record(match.match(invoice, listOf(accrual(AccrualLine.of(1, chips, 10, usd("18.00"))))), clock)

        invoice.status shouldBe InvoiceStatus.Matched(today)
    }

    @Test
    fun `the tolerance judges the case cost by percentage and the line by amount`() {
        val tolerance = MatchTolerance.of(Percentage.of("2"), BigDecimal("10.00"))

        tolerance.allows(usd("18.00"), usd("18.36"), 10) shouldBe true
        tolerance.allows(usd("18.00"), usd("17.64"), 10) shouldBe true
        tolerance.allows(usd("18.00"), usd("18.37"), 1) shouldBe false
        tolerance.allows(usd("18.00"), usd("18.36"), 28) shouldBe false
        tolerance.allows(usd("0.00"), usd("0.01"), 1) shouldBe false
        tolerance.allows(usd("0.00"), usd("0.00"), 1) shouldBe true
        shouldThrow<IllegalArgumentException> { MatchTolerance.of(Percentage.of("2"), BigDecimal("-1")) }
    }

    @Test
    fun `an invoice bills each order line once, in one currency, with cases and a cost`() {
        shouldThrow<InvoiceLineRepeated> {
            invoice(
                InvoiceLine.of(1, 1, usd("1.00")),
                InvoiceLine.of(1, 2, usd("1.00")),
            )
        }
        shouldThrow<InvoiceInMixedCurrencies> {
            invoice(InvoiceLine.of(1, 1, usd("1.00")), InvoiceLine.of(2, 1, Money.of("1.00", "CAD")))
        }
        shouldThrow<IllegalArgumentException> { invoice() }
        shouldThrow<IllegalArgumentException> { InvoiceLine.of(1, 0, usd("1.00")) }
        shouldThrow<IllegalArgumentException> { InvoiceLine.of(0, 1, usd("1.00")) }
        shouldThrow<IllegalArgumentException> { VendorInvoiceNumber.of(" ") }
        shouldThrow<IllegalArgumentException> { AccrualLine.of(1, chips, 2, usd("1.00"), casesInvoiced = 3) }
    }

    private fun invoice(vararg lines: InvoiceLine) =
        VendorInvoice.enter(
            InvoiceDetails(vendorId, VendorInvoiceNumber.of("INV-1001"), today, orderId, lines.toList()),
            "clerk",
            clock,
        )

    private fun accrual(
        vararg lines: AccrualLine,
        receivedOn: BusinessDate = today.minusDays(2),
    ) = Accrual.record(
        AccruedReceipt(
            ReceiptId.generate(clock),
            "100001",
            orderId,
            "200001",
            vendorId,
            WarehouseId.generate(clock),
            receivedOn,
        ),
        lines.toList(),
        clock,
    )

    private fun usd(amount: String) = Money.of(amount, "USD")
}
