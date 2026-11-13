package com.retailplatform.payables.domain

import com.retailplatform.payables.api.events.DirectDeliveryInvoiceMatchException
import com.retailplatform.payables.api.events.DirectDeliveryInvoiceMatched
import com.retailplatform.shared.BusinessDate
import com.retailplatform.shared.DirectDeliveryId
import com.retailplatform.shared.ItemId
import com.retailplatform.shared.Money
import com.retailplatform.shared.Percentage
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

class TwoWayMatchTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-13T15:00:00Z"), ZoneOffset.UTC)
    private val today = BusinessDate.today(clock)
    private val ticket = BilledTicket(VendorId.generate(clock), StoreId.generate(clock), "T-1001")
    private val cola = ItemId.generate(clock)
    private val chips = ItemId.generate(clock)
    private val match = TwoWayMatch(MatchTolerance.of(Percentage.of("2"), BigDecimal("10.00")))

    @Test
    fun `an invoice for exactly what the store received, within tolerance, matches`() {
        val accrual = accrual(line(cola, 20, "1.25"), line(chips, 10, "0.80"))
        val invoice = invoice(line(cola, 20, "1.26"), line(chips, 10, "0.80"))

        val outcome = match.match(invoice, accrual)
        invoice.record(outcome, clock)

        outcome shouldBe TicketMatchOutcome.Matched(usd("33.00"))
        invoice.status shouldBe TicketInvoiceStatus.Matched(today)
        val event = invoice.releaseEvents().single().shouldBeInstanceOf<DirectDeliveryInvoiceMatched>()
        event.invoiceAmount shouldBe BigDecimal("33.2000")
        event.accruedAmount shouldBe BigDecimal("33.0000")
        event.costVariance shouldBe BigDecimal("0.2000")
        event.invoice.ticketNumber shouldBe "T-1001"
        val voucher = invoice.matched()
        voucher.amount shouldBe usd("33.20")
        voucher.invoice.purchaseOrderId shouldBe null
        shouldThrow<InvoiceAlreadyMatched> { invoice.record(outcome, clock) }
    }

    @Test
    fun `wrong items, wrong quantities, and prices over tolerance are each an exception`() {
        val accrual = accrual(line(cola, 20, "1.25"), line(chips, 10, "0.80"))
        val extra = ItemId.generate(clock)
        val invoice = invoice(line(cola, 18, "1.25"), line(extra, 5, "2.00"))

        val outcome = match.match(invoice, accrual).shouldBeInstanceOf<TicketMatchOutcome.Exceptions>()
        invoice.record(outcome, clock)

        outcome.exceptions.map { it.itemId to it.reason } shouldBe
            listOf(
                cola to TicketExceptionReason.QUANTITY_DIFFERS,
                extra to TicketExceptionReason.ITEM_NOT_ON_TICKET,
                chips to TicketExceptionReason.ITEM_NOT_BILLED,
            )
        invoice.status shouldBe TicketInvoiceStatus.Exception(outcome.exceptions)
        val event = invoice.releaseEvents().single().shouldBeInstanceOf<DirectDeliveryInvoiceMatchException>()
        event.exceptions.map { it.reason } shouldBe listOf("QUANTITY_DIFFERS", "ITEM_NOT_ON_TICKET", "ITEM_NOT_BILLED")
        shouldThrow<IllegalStateException> { invoice.matched() }

        match
            .match(invoice(line(cola, 20, "1.40"), line(chips, 10, "0.80")), accrual)
            .shouldBeInstanceOf<TicketMatchOutcome.Exceptions>()
            .exceptions
            .single()
            .reason shouldBe TicketExceptionReason.PRICE_OVER_TOLERANCE
    }

    @Test
    fun `an invoice waits while its ticket has not been checked in or was already invoiced`() {
        val invoice = invoice(line(cola, 20, "1.25"))

        val notReceived = match.match(invoice, null).shouldBeInstanceOf<TicketMatchOutcome.Exceptions>()
        notReceived.exceptions.single().reason shouldBe TicketExceptionReason.TICKET_NOT_RECEIVED
        notReceived.exceptions.single().itemId shouldBe null

        val relieved = accrual(line(cola, 20, "1.25")).also { it.relieve(today) }
        relieved.isOpen shouldBe false
        relieved.relievedOn shouldBe today
        match.match(invoice, relieved).shouldBeInstanceOf<TicketMatchOutcome.Exceptions>()
        shouldThrow<IllegalStateException> { relieved.relieve(today) }
    }

    @Test
    fun `an override accepts a price, by someone else than who entered it, and nothing else`() {
        val accrual = accrual(line(cola, 20, "1.25"))
        val overpriced = invoice(line(cola, 20, "1.40"))
        val override = MatchOverride.of("approver", "New price list not loaded yet")

        val accepted = match.match(overpriced, accrual, acceptPrice = true)
        shouldThrow<OverrideByInvoiceCreator> { overpriced.record(accepted, clock, MatchOverride.of("clerk", "Mine")) }
        overpriced.record(accepted, clock, override)

        overpriced.status shouldBe TicketInvoiceStatus.Matched(today, override)
        overpriced.matched().overrideApprovedBy shouldBe "approver"
        match
            .match(invoice(line(cola, 21, "1.40")), accrual, acceptPrice = true)
            .shouldBeInstanceOf<TicketMatchOutcome.Exceptions>()
        match
            .match(invoice(TicketAmountLine.of(cola, 20, Money.of("1.25", "CAD"))), accrual, acceptPrice = true)
            .shouldBeInstanceOf<TicketMatchOutcome.Exceptions>()
    }

    @Test
    fun `an invoice bills each item once, in one currency`() {
        shouldThrow<InvoiceLineRepeated> { invoice(line(cola, 1, "1.00"), line(cola, 2, "1.00")) }
        shouldThrow<InvoiceInMixedCurrencies> {
            invoice(line(cola, 1, "1.00"), TicketAmountLine.of(chips, 1, Money.of("1.00", "CAD")))
        }
        shouldThrow<IllegalArgumentException> { invoice() }
        shouldThrow<IllegalArgumentException> { TicketAmountLine.of(cola, 0, usd("1.00")) }
        shouldThrow<IllegalArgumentException> { TicketAmountLine.of(cola, 1, usd("-1.00")) }
    }

    private fun invoice(vararg lines: TicketAmountLine) =
        DirectDeliveryInvoice.enter(
            TicketInvoiceDetails(ticket, VendorInvoiceNumber.of("DSD-1"), today, lines.toList()),
            "clerk",
            clock,
        )

    private fun accrual(vararg lines: TicketAmountLine) =
        DirectDeliveryAccrual.record(
            DeliveredTicket(
                DirectDeliveryId.generate(clock),
                "100001",
                ticket.vendorId,
                ticket.storeId,
                ticket.ticketNumber,
                today.minusDays(2),
            ),
            lines.toList(),
        )

    private fun line(
        itemId: ItemId,
        units: Int,
        unitCost: String,
    ) = TicketAmountLine.of(itemId, units, usd(unitCost))

    private fun usd(amount: String) = Money.of(amount, "USD")
}
