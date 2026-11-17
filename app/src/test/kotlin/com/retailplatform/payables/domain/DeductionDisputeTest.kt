package com.retailplatform.payables.domain

import com.retailplatform.shared.BusinessDate
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

class DeductionDisputeTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-17T15:00:00Z"), ZoneOffset.UTC)
    private val today = BusinessDate.today(clock)
    private val ticket = BilledTicket(VendorId.generate(clock), StoreId.generate(clock), "T-1001")
    private val deduction =
        DisputedDeduction(
            DeductionNoticeId.generate(clock),
            DeductionNoticeNumber.of("100001"),
            ticket,
            usd("12.52"),
            deductedBy = "manager",
        )
    private val other = MatchOverride.of("controller", "Driver's signed ticket shows 19 units")

    @Test
    fun `part of a disputed deduction is conceded, vouchered against the vendor's claim, and told to the ledger`() {
        val dispute = recorded()
        dispute.status shouldBe DisputeStatus.Open
        dispute.recordedOn shouldBe today
        shouldThrow<IllegalStateException> { dispute.payable() }

        val conceded = dispute.concede(usd("1.26"), other, clock)

        conceded.status shouldBe DisputeStatus.Conceded(today, other, usd("1.26"))
        val voucher = conceded.payable()
        voucher.amount shouldBe usd("1.26")
        voucher.invoice.invoiceId.value shouldBe dispute.id.value
        voucher.invoice.vendorInvoiceNumber shouldBe VendorInvoiceNumber.of("CLAIM-7")
        voucher.invoice.invoiceDate shouldBe today.minusDays(1)
        voucher.overrideApprovedBy shouldBe "controller"
        val event = conceded.concession(clock)
        event.amount shouldBe BigDecimal("1.2600")
        event.deductionNoticeNumber shouldBe "100001"
        event.vendorReference shouldBe "CLAIM-7"
        event.ticketNumber shouldBe "T-1001"
        // The whole deduction may be conceded, but no more, and not in another currency.
        dispute.concede(usd("12.52"), other, clock).status shouldBe DisputeStatus.Conceded(today, other, usd("12.52"))
        shouldThrow<ConcessionOutOfRange> { dispute.concede(usd("12.53"), other, clock) }
        shouldThrow<ConcessionOutOfRange> { dispute.concede(usd("0.00"), other, clock) }
        shouldThrow<ConcessionOutOfRange> { dispute.concede(Money.of("1.00", "CAD"), other, clock) }
    }

    @Test
    fun `a dispute is decided once, and not by the person who made the deduction`() {
        val dispute = recorded()
        val deductor = MatchOverride.of("manager", "My count stands")

        shouldThrow<DisputeDecidedByDeductor> { dispute.uphold(deductor, clock) }
        shouldThrow<DisputeDecidedByDeductor> { dispute.concede(usd("1.26"), deductor, clock) }
        val upheld = dispute.uphold(other, clock)

        upheld.status shouldBe DisputeStatus.Upheld(today, other)
        shouldThrow<IllegalStateException> { upheld.payable() }
        shouldThrow<IllegalStateException> { upheld.concession(clock) }
        shouldThrow<DisputeAlreadyDecided> { upheld.concede(usd("1.26"), other, clock) }
        shouldThrow<DisputeAlreadyDecided> { dispute.concede(usd("1.26"), other, clock).uphold(other, clock) }
        shouldThrow<DisputeDateInvalid> {
            DeductionDispute.record(deduction, claim(today.plusDays(1)), "clerk", clock)
        }
        shouldThrow<IllegalArgumentException> {
            DisputeClaim.of(today, VendorInvoiceNumber.of("CLAIM-8"), " ")
        }
    }

    private fun recorded() = DeductionDispute.record(deduction, claim(today.minusDays(1)), "clerk", clock)

    private fun claim(receivedOn: BusinessDate) =
        DisputeClaim.of(receivedOn, VendorInvoiceNumber.of("CLAIM-7"), "We delivered 19 of the 20 colas billed")

    private fun usd(amount: String) = Money.of(amount, "USD")
}
