package com.retailplatform.payables.domain

import com.retailplatform.shared.BusinessDate
import com.retailplatform.shared.DirectDeliveryId
import com.retailplatform.shared.ItemId
import com.retailplatform.shared.Money
import com.retailplatform.shared.StoreId
import com.retailplatform.shared.VendorId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class DeductionRecoveryTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-17T15:00:00Z"), ZoneOffset.UTC)
    private val today = BusinessDate.today(clock)
    private val cola = ItemId.generate(clock)
    private val chips = ItemId.generate(clock)
    private val ticket =
        DeliveredTicket(
            DirectDeliveryId.generate(clock),
            "100098",
            VendorId.generate(clock),
            StoreId.generate(clock),
            "T-1001",
            today,
        )

    // 5 colas held back at 1.26 and 2 chips at 0.80: 7.90 in all.
    private val deduction =
        listOf(DeductionLine.of(cola, 20, 15, usd("1.26")), DeductionLine.of(chips, 10, 8, usd("0.80")))

    @Test
    fun `units an adjustment covers can no longer be conceded, item by item and at the cost billed`() {
        DeductionRecovery.concedable(deduction, emptyList()) shouldBe usd("7.90")

        // The store found 2 of the colas: 3 colas and the chips are left.
        val found = adjustment(TicketAmountLine.of(cola, 2, usd("1.25")))
        DeductionRecovery.concedable(deduction, listOf(found)) shouldBe usd("5.38")
        // Paid counts as open does; more colas found than were held back covers no more than was held back.
        val paid = found.resolve(AdjustmentResolution.Paid(today, manager, VendorInvoiceNumber.of("DM-1"), today))
        val more = adjustment(TicketAmountLine.of(cola, 9, usd("1.25")))
        DeductionRecovery.concedable(deduction, listOf(paid, more)) shouldBe usd("1.60")
    }

    @Test
    fun `adjustments written off, of another kind, for other items, or without lines cover nothing`() {
        val writtenOff =
            adjustment(
                TicketAmountLine.of(cola, 5, usd("1.25")),
            ).resolve(AdjustmentResolution.WrittenOff(today, manager))
        val returnReversed =
            adjustment(TicketAmountLine.of(cola, 5, usd("1.25")))
                .copy(kind = DeliveryAdjustmentKind.RETURN_REVERSED_AFTER_CREDIT_USED)
        val otherItem = adjustment(TicketAmountLine.of(ItemId.generate(clock), 5, usd("2.00")))
        val withoutLines = adjustment()

        DeductionRecovery.concedable(deduction, listOf(writtenOff, returnReversed, otherItem, withoutLines)) shouldBe
            usd("7.90")
    }

    @Test
    fun `part of an adjustment is paid by units and the rest is written off`() {
        val found = adjustment(TicketAmountLine.of(cola, 5, usd("1.25")), TicketAmountLine.of(chips, 2, usd("0.80")))
        found.paidAmount shouldBe usd("0.00")
        found.writtenOffAmount shouldBe usd("0.00")

        val part = found.partOf(listOf(cola to 3))
        val paid = found.resolve(AdjustmentResolution.Paid(today, manager, VendorInvoiceNumber.of("DM-2"), today, part))

        part shouldBe listOf(TicketAmountLine.of(cola, 3, usd("1.25")))
        paid.paidAmount shouldBe usd("3.75")
        paid.writtenOffAmount shouldBe usd("4.10")
        paid.payable().amount shouldBe usd("3.75")
        val event = paid.resolved(clock)
        event.resolution shouldBe "PAID"
        event.amount shouldBe BigDecimal("3.7500")
        event.writtenOffAmount shouldBe BigDecimal("4.1000")
        // Only the 3 colas paid still cover units of the deduction: 2 colas and the chips can be conceded.
        DeductionRecovery.concedable(deduction, listOf(paid)) shouldBe usd("4.12")

        // Naming every unit is paying the whole; units the adjustment is not for are refused.
        found.partOf(listOf(chips to 2, cola to 5)) shouldBe null
        shouldThrow<AdjustmentPartInvalid> { found.partOf(listOf(cola to 6)) }
        shouldThrow<AdjustmentPartInvalid> { found.partOf(listOf(cola to 0)) }
        shouldThrow<AdjustmentPartInvalid> { found.partOf(listOf(cola to 1, cola to 1)) }
        shouldThrow<AdjustmentPartInvalid> { found.partOf(listOf(ItemId.generate(clock) to 1)) }
        shouldThrow<AdjustmentPartInvalid> { found.partOf(emptyList()) }
        shouldThrow<AdjustmentPartInvalid> { adjustment().partOf(listOf(cola to 1)) }
    }

    @Test
    fun `a dispute is conceded for no more than is left to concede`() {
        val notice = DeductionNoticeNumber.of("100001")
        val billed = BilledTicket(ticket.vendorId, ticket.storeId, ticket.ticketNumber)
        val dispute =
            DeductionDispute.record(
                DisputedDeduction(DeductionNoticeId.generate(clock), notice, billed, usd("7.90"), "manager"),
                DisputeClaim.of(today, VendorInvoiceNumber.of("CLAIM-1"), "We delivered them all"),
                "clerk",
                clock,
            )
        val other = MatchOverride.of("controller", "Driver's ticket is signed")

        shouldThrow<DeductionAlreadyRecovered> { dispute.concede(usd("5.39"), other, clock, concedable = usd("5.38")) }
        dispute.concede(usd("5.38"), other, clock, concedable = usd("5.38")).status shouldBe
            DisputeStatus.Conceded(today, other, usd("5.38"))
        shouldThrow<ConcessionOutOfRange> { dispute.concede(usd("7.91"), other, clock, concedable = usd("5.38")) }
    }

    private val manager = MatchOverride.of("manager", "Settled with the vendor")

    private fun adjustment(vararg lines: TicketAmountLine) =
        DeliveryAdjustment(
            DeliveryAdjustmentId.generate(clock),
            ticket,
            DeliveryAdjustmentKind.RECEIVED_AFTER_INVOICE,
            lines.map { it.amount }.fold(usd("0.00"), Money::plus),
            lines = lines.toList(),
        )

    private fun usd(amount: String) = Money.of(amount, "USD")
}
