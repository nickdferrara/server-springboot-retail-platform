package com.retailplatform.payables.domain

import com.retailplatform.payables.api.events.PayableVoucherReleased
import com.retailplatform.payables.api.events.PaymentIssued
import com.retailplatform.shared.BusinessDate
import com.retailplatform.shared.DirectDeliveryId
import com.retailplatform.shared.ItemId
import com.retailplatform.shared.Money
import com.retailplatform.shared.Percentage
import com.retailplatform.shared.PurchaseOrderId
import com.retailplatform.shared.ReceiptId
import com.retailplatform.shared.StoreId
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

class PaymentRunTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-12T15:00:00Z"), ZoneOffset.UTC)
    private val today = BusinessDate.today(clock)
    private val acme = VendorId.generate(clock)
    private val bolt = VendorId.generate(clock)
    private val net30 = VendorTerms.of(30)
    private val twoTenNet30 = VendorTerms.of(30, EarlyPaymentDiscount(Percentage.of("2"), 10))
    private var numbers = 100000

    @Test
    fun `a voucher is released for a matched invoice, due and discounted by the vendor's terms`() {
        val voucher = voucher(acme, "200.00", twoTenNet30, invoiceDate = today.minusDays(3))

        voucher.amount shouldBe usd("200.00")
        voucher.terms.dueOn shouldBe today.plusDays(27)
        voucher.terms.discount shouldBe VoucherDiscount(Percentage.of("2"), today.plusDays(7))
        voucher.status shouldBe VoucherStatus.Released
        voucher.releasedOn shouldBe today
        val event = voucher.releaseEvents().single().shouldBeInstanceOf<PayableVoucherReleased>()
        event.amount shouldBe BigDecimal("200.0000")
        event.dueOn shouldBe today.plusDays(27).value
        event.voucherNumber shouldBe voucher.number.value
        event.overrideApprovedBy shouldBe null
    }

    @Test
    fun `the discount is available through its last day and not after`() {
        val voucher = voucher(acme, "200.00", twoTenNet30, invoiceDate = today)

        voucher.discountOn(today.plusDays(10)) shouldBe usd("4.00")
        voucher.discountOn(today.plusDays(11)) shouldBe usd("0.00")
        voucher(acme, "200.00", net30).discountOn(today) shouldBe usd("0.00")
    }

    @Test
    fun `a window selects vouchers that are due or whose discount runs out before the next run`() {
        val window = PaymentWindow.of(today, today.plusDays(7))

        window.selects(voucher(acme, "10.00", net30, invoiceDate = today.minusDays(23))) shouldBe true
        window.selects(voucher(acme, "10.00", net30, invoiceDate = today.minusDays(22))) shouldBe false
        // Due in 25 days, but the discount runs out in five: paying now is worth it.
        window.selects(voucher(acme, "10.00", twoTenNet30, invoiceDate = today.minusDays(5))) shouldBe true
        // The discount ran out yesterday, so there is nothing to gain by paying before the due date.
        window.selects(voucher(acme, "10.00", twoTenNet30, invoiceDate = today.minusDays(11))) shouldBe false
        // The discount is still good at the next run.
        window.selects(voucher(acme, "10.00", twoTenNet30, invoiceDate = today)) shouldBe false
        shouldThrow<IllegalArgumentException> { PaymentWindow.of(today, today.minusDays(1)) }
    }

    @Test
    fun `a proposed run reserves its vouchers and takes the discounts available on the payment date`() {
        val discounted = voucher(acme, "200.00", twoTenNet30, invoiceDate = today.minusDays(5))
        val plain = voucher(bolt, "50.00", net30, invoiceDate = today.minusDays(30))

        val run =
            PaymentRun.propose(
                PaymentWindow.of(today, today.plusDays(7)),
                listOf(discounted, plain),
                emptyList(),
                "clerk",
                clock,
            )

        run.status shouldBe PaymentRunStatus.Proposed
        run.lines.map { it.discount } shouldBe listOf(usd("4.00"), usd("0.00"))
        run.lines.map { it.net } shouldBe listOf(usd("196.00"), usd("50.00"))
        discounted.status shouldBe VoucherStatus.Scheduled(run.id)
        shouldThrow<VoucherNotReleased> {
            PaymentRun.propose(PaymentWindow.of(today, today), listOf(discounted), emptyList(), "clerk", clock)
        }
    }

    @Test
    fun `a run needs something to pay and a payment date that has not passed`() {
        shouldThrow<NothingToPay> {
            PaymentRun.propose(PaymentWindow.of(today, today), emptyList(), emptyList(), "clerk", clock)
        }
        shouldThrow<PaymentDateInPast> {
            PaymentRun.propose(
                PaymentWindow.of(today.minusDays(1), today),
                listOf(voucher(acme, "10.00", net30)),
                emptyList(),
                "clerk",
                clock,
            )
        }
    }

    @Test
    fun `approving a run issues one payment per vendor and settles the vouchers`() {
        val first = voucher(acme, "200.00", twoTenNet30, invoiceDate = today.minusDays(5))
        val second = voucher(acme, "100.00", net30, invoiceDate = today.minusDays(30))
        val other = voucher(bolt, "50.00", net30, invoiceDate = today.minusDays(30))
        val run =
            PaymentRun.propose(
                PaymentWindow.of(today, today),
                listOf(first, second, other),
                emptyList(),
                "clerk",
                clock,
            )

        val payments = run.approve("manager", ::nextPaymentNumber, clock)

        run.status shouldBe PaymentRunStatus.Approved("manager", today)
        payments.map { it.vendorId } shouldBe listOf(acme, bolt)
        val toAcme = payments.first()
        toAcme.gross shouldBe usd("300.00")
        toAcme.discount shouldBe usd("4.00")
        toAcme.net shouldBe usd("296.00")
        toAcme.paidOn shouldBe today
        val event = toAcme.releaseEvents().single().shouldBeInstanceOf<PaymentIssued>()
        event.grossAmount shouldBe BigDecimal("300.0000")
        event.discountAmount shouldBe BigDecimal("4.0000")
        event.netAmount shouldBe BigDecimal("296.0000")
        event.businessDate shouldBe today
        event.vouchers.map { it.voucherNumber } shouldBe listOf(first.number.value, second.number.value)

        first.pay(run.id, toAcme.id, toAcme.paidOn)
        first.status shouldBe VoucherStatus.Paid(run.id, toAcme.id, today)
        shouldThrow<IllegalStateException> { first.pay(run.id, toAcme.id, today) }
        shouldThrow<PaymentRunNotProposed> { run.approve("manager", ::nextPaymentNumber, clock) }
        shouldThrow<PaymentRunNotProposed> { run.cancel("clerk", clock) }
    }

    @Test
    fun `a run is not approved by its proposer or after its payment date`() {
        val run =
            PaymentRun.propose(
                PaymentWindow.of(today, today),
                listOf(voucher(acme, "10.00", net30)),
                emptyList(),
                "clerk",
                clock,
            )
        val tomorrow = Clock.offset(clock, java.time.Duration.ofDays(1))

        shouldThrow<PaymentRunApprovedByProposer> { run.approve("clerk", ::nextPaymentNumber, clock) }
        shouldThrow<PaymentRunOutOfDate> { run.approve("manager", ::nextPaymentNumber, tomorrow) }
        run.status shouldBe PaymentRunStatus.Proposed
    }

    @Test
    fun `cancelling a run gives its vouchers back`() {
        val voucher = voucher(acme, "10.00", net30)
        val run = PaymentRun.propose(PaymentWindow.of(today, today), listOf(voucher), emptyList(), "clerk", clock)

        run.cancel("clerk", clock)
        voucher.unschedule(run.id)

        run.status shouldBe PaymentRunStatus.Cancelled("clerk", today)
        voucher.status shouldBe VoucherStatus.Released
        shouldThrow<IllegalStateException> { voucher.unschedule(run.id) }
    }

    @Test
    fun `a run deducts the credits that fit whole within what it pays their vendor, oldest first`() {
        val owed = voucher(acme, "100.00", net30, invoiceDate = today.minusDays(30))
        val oldest = credit(acme, "T-1", units = 30, receivedOn = today.minusDays(9))
        val tooBig = credit(acme, "T-2", units = 80, receivedOn = today.minusDays(5))
        val newest = credit(acme, "T-3", units = 70, receivedOn = today.minusDays(1))
        val elsewhere = credit(bolt, "T-4", units = 1, receivedOn = today.minusDays(9))
        val window = PaymentWindow.of(today, today)

        val run = PaymentRun.propose(window, listOf(owed), listOf(oldest, tooBig, newest, elsewhere), "clerk", clock)

        // 30.00 and then 70.00 fit within 100.00; 80.00 does not fit after the first, and bolt is not being paid.
        run.credits.map { it.creditId } shouldBe listOf(oldest.id, newest.id)
        oldest.status shouldBe VendorCreditStatus.Scheduled(run.id)
        tooBig.isOpen shouldBe true
        elsewhere.isOpen shouldBe true

        val payment = run.approve("manager", ::nextPaymentNumber, clock).single()
        payment.gross shouldBe usd("100.00")
        payment.credit shouldBe usd("100.00")
        payment.net shouldBe usd("0.00")
        val event = payment.releaseEvents().single().shouldBeInstanceOf<PaymentIssued>()
        event.creditAmount shouldBe BigDecimal("100.0000")
        event.netAmount shouldBe BigDecimal("0.0000")

        oldest.apply(run.id, payment.id, payment.paidOn)
        oldest.status shouldBe VendorCreditStatus.Applied(run.id, payment.id, today)
        shouldThrow<IllegalStateException> { oldest.apply(run.id, payment.id, today) }
        shouldThrow<IllegalStateException> { oldest.schedule(run.id) }
        newest.unschedule(run.id)
        newest.isOpen shouldBe true
        shouldThrow<IllegalStateException> { newest.unschedule(run.id) }
    }

    @Test
    fun `terms, numbers, and overrides refuse values that make no sense`() {
        shouldThrow<IllegalArgumentException> { VendorTerms.of(-1) }
        shouldThrow<IllegalArgumentException> { VendorTerms.of(30, EarlyPaymentDiscount(Percentage.of("2"), -1)) }
        shouldThrow<IllegalArgumentException> { VendorTerms.of(30, EarlyPaymentDiscount(Percentage.of("0"), 10)) }
        shouldThrow<IllegalArgumentException> { VoucherNumber.of("V-1") }
        shouldThrow<IllegalArgumentException> { PaymentNumber.of("") }
        shouldThrow<IllegalArgumentException> { MatchOverride.of("manager", "  ") }
        shouldThrow<IllegalArgumentException> { MatchOverride.of("manager", "x".repeat(201)) }
        MatchOverride.of("manager", " agreed with the buyer ").reason shouldBe "agreed with the buyer"
    }

    private fun voucher(
        vendorId: VendorId,
        amount: String,
        terms: VendorTerms,
        invoiceDate: BusinessDate = today,
    ): Voucher {
        val orderId = PurchaseOrderId.generate(clock)
        val number = ++numbers
        val invoice =
            VendorInvoice.enter(
                InvoiceDetails(
                    vendorId,
                    VendorInvoiceNumber.of("INV-$number"),
                    invoiceDate,
                    orderId,
                    listOf(InvoiceLine.of(1, 1, usd(amount))),
                ),
                "clerk",
                clock,
            )
        val accrual =
            Accrual.record(
                AccruedReceipt(
                    ReceiptId.generate(clock),
                    "100001",
                    orderId,
                    "200001",
                    vendorId,
                    WarehouseId.generate(clock),
                    today,
                ),
                listOf(AccrualLine.of(1, ItemId.generate(clock), 1, usd(amount))),
                clock,
            )
        val match = ThreeWayMatch(MatchTolerance.of(Percentage.of("2"), BigDecimal("10.00")))
        invoice.record(match.match(invoice, listOf(accrual)), clock)
        return Voucher.release(VoucherNumber.of(number.toString()), invoice.matched(), terms, clock)
    }

    /** A credit for [units] units at 1.00 each. */
    private fun credit(
        vendorId: VendorId,
        ticket: String,
        units: Int,
        receivedOn: BusinessDate,
    ) = VendorCredit.record(
        DeliveredTicket(
            DirectDeliveryId.generate(clock),
            "100001",
            vendorId,
            StoreId.generate(clock),
            ticket,
            receivedOn,
        ),
        listOf(TicketAmountLine.of(ItemId.generate(clock), units, usd("1.00"))),
        clock,
    )

    private fun nextPaymentNumber() = PaymentNumber.of((++numbers).toString())

    private fun usd(amount: String) = Money.of(amount, "USD")
}
