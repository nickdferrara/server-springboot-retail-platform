package com.retailplatform.payables

import com.retailplatform.TestcontainersConfiguration
import com.retailplatform.payables.api.events.AccrualRecorded
import com.retailplatform.payables.api.events.InvoiceMatchException
import com.retailplatform.payables.api.events.InvoiceMatched
import com.retailplatform.payables.api.events.PayableVoucherReleased
import com.retailplatform.payables.api.events.PaymentIssued
import com.retailplatform.payables.application.AccrualQueries
import com.retailplatform.payables.application.ApprovePaymentRun
import com.retailplatform.payables.application.CancelPaymentRun
import com.retailplatform.payables.application.DirectDeliveryPayablesQueries
import com.retailplatform.payables.application.EnterVendorInvoice
import com.retailplatform.payables.application.InvoiceQueries
import com.retailplatform.payables.application.OverrideMatchException
import com.retailplatform.payables.application.PaymentQueries
import com.retailplatform.payables.application.ProposePaymentRun
import com.retailplatform.payables.application.RecordAccrual
import com.retailplatform.payables.application.RecordAccrualCommand
import com.retailplatform.payables.application.RecordDirectDelivery
import com.retailplatform.payables.application.RecordDirectDeliveryCommand
import com.retailplatform.payables.application.RematchVendorInvoice
import com.retailplatform.payables.application.VendorTermsRepository
import com.retailplatform.payables.application.VoucherQueries
import com.retailplatform.payables.domain.AccrualLine
import com.retailplatform.payables.domain.AccruedReceipt
import com.retailplatform.payables.domain.InvoiceAlreadyEntered
import com.retailplatform.payables.domain.InvoiceAlreadyMatched
import com.retailplatform.payables.domain.InvoiceDetails
import com.retailplatform.payables.domain.InvoiceLine
import com.retailplatform.payables.domain.InvoiceStatus
import com.retailplatform.payables.domain.MatchExceptionReason
import com.retailplatform.payables.domain.MatchOverride
import com.retailplatform.payables.domain.NothingToPay
import com.retailplatform.payables.domain.OverrideByInvoiceCreator
import com.retailplatform.payables.domain.OverrideDoesNotResolve
import com.retailplatform.payables.domain.PaymentDateInPast
import com.retailplatform.payables.domain.PaymentRunApprovedByProposer
import com.retailplatform.payables.domain.PaymentRunId
import com.retailplatform.payables.domain.PaymentRunNotProposed
import com.retailplatform.payables.domain.PaymentRunStatus
import com.retailplatform.payables.domain.PaymentWindow
import com.retailplatform.payables.domain.VendorInvoiceNumber
import com.retailplatform.payables.domain.VendorTerms
import com.retailplatform.payables.domain.VoucherDiscount
import com.retailplatform.payables.domain.VoucherStatus
import com.retailplatform.platform.security.Roles
import com.retailplatform.receiving.api.events.DirectDeliveryFacts
import com.retailplatform.receiving.api.events.DirectDeliveryLine
import com.retailplatform.receiving.api.events.DirectDeliveryReceived
import com.retailplatform.receiving.api.events.GoodsReceived
import com.retailplatform.receiving.api.events.GoodsReceivedLine
import com.retailplatform.receiving.api.events.ReceiptFacts
import com.retailplatform.shared.AccrualId
import com.retailplatform.shared.BusinessDate
import com.retailplatform.shared.DirectDeliveryId
import com.retailplatform.shared.EventId
import com.retailplatform.shared.InvoiceId
import com.retailplatform.shared.ItemId
import com.retailplatform.shared.Money
import com.retailplatform.shared.PageQuery
import com.retailplatform.shared.PaymentId
import com.retailplatform.shared.Percentage
import com.retailplatform.shared.PurchaseOrderId
import com.retailplatform.shared.ReceiptId
import com.retailplatform.shared.ResourceNotFound
import com.retailplatform.shared.StoreId
import com.retailplatform.shared.VendorId
import com.retailplatform.shared.VoucherId
import com.retailplatform.shared.WarehouseId
import com.retailplatform.vendors.api.events.PaymentTermsFacts
import com.retailplatform.vendors.api.events.VendorCreated
import com.retailplatform.vendors.api.events.VendorTermsChanged
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.modulith.test.ApplicationModuleTest
import org.springframework.modulith.test.Scenario
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.test.context.support.WithMockUser
import java.math.BigDecimal
import java.time.Clock
import java.util.Optional

@ApplicationModuleTest
@Import(TestcontainersConfiguration::class)
@WithMockUser(username = "clerk", authorities = [Roles.ACCOUNTING_CLERK])
class PayablesModuleTest(
    @Autowired private val recordAccrual: RecordAccrual,
    @Autowired private val accruals: AccrualQueries,
    @Autowired private val enterVendorInvoice: EnterVendorInvoice,
    @Autowired private val rematchVendorInvoice: RematchVendorInvoice,
    @Autowired private val invoices: InvoiceQueries,
    @Autowired private val overrideMatchException: OverrideMatchException,
    @Autowired private val vouchers: VoucherQueries,
    @Autowired private val vendorTerms: VendorTermsRepository,
    @Autowired private val proposePaymentRun: ProposePaymentRun,
    @Autowired private val approvePaymentRun: ApprovePaymentRun,
    @Autowired private val cancelPaymentRun: CancelPaymentRun,
    @Autowired private val payments: PaymentQueries,
    @Autowired private val recordDirectDelivery: RecordDirectDelivery,
    @Autowired private val directDeliveries: DirectDeliveryPayablesQueries,
    @Autowired private val clock: Clock,
) {
    private val today get() = BusinessDate.today(clock)

    @Test
    fun `goods received are accrued once at purchase order cost`(scenario: Scenario) {
        val vendorId = VendorId.generate(clock)
        val receipt = receiptFacts(vendorId, "300001")
        val lines =
            listOf(
                GoodsReceivedLine(1, ItemId.generate(clock), 12, 4, BigDecimal("18.0000")),
                GoodsReceivedLine(2, ItemId.generate(clock), 24, 5, BigDecimal("24.0000")),
            )
        val event = GoodsReceived(EventId.generate(clock), clock.instant(), today, receipt, "USD", lines, false)

        scenario
            .publish(event)
            .andWaitForEventOfType(AccrualRecorded::class.java)
            .matching { it.receiptId == receipt.receiptId }
            .toArriveAndVerify { recorded ->
                recorded.amount shouldBe BigDecimal("192.0000")
                recorded.vendorId shouldBe vendorId
                recorded.businessDate shouldBe today
            }

        // The same receipt arriving again, as a redelivered or re-published event would, accrues nothing more.
        recordAccrual.handle(
            RecordAccrualCommand(
                accruedReceipt(vendorId, receipt.receiptId, today),
                listOf(AccrualLine.of(1, ItemId.generate(clock), 4, usd("18.00"))),
            ),
        )

        val open = accruals.open(vendorId, PageQuery.of())
        open.total shouldBe 1
        val accrual = open.items.single()
        accrual.amount shouldBe usd("192.00")
        accrual.openAmount shouldBe usd("192.00")
        accrual.lines.map { it.casesReceived } shouldBe listOf(4, 5)
        accruals.find(accrual.id).receipt.receiptNumber shouldBe "300001"
        shouldThrow<ResourceNotFound> { accruals.find(AccrualId.generate(clock)) }
    }

    @Test
    fun `open accruals are totalled by vendor and flagged when they have waited too long`() {
        val recent = VendorId.generate(clock)
        val overdue = VendorId.generate(clock)
        accrue(recent, receivedOn = today.minusDays(2), amount = "50.00")
        accrue(recent, receivedOn = today.minusDays(10), amount = "25.00")
        accrue(overdue, receivedOn = today.minusDays(61), amount = "40.00")

        val balances = accruals.balances().associateBy { it.vendorId }

        balances.getValue(recent).openAmount shouldBe usd("75.00")
        balances.getValue(recent).openAccruals shouldBe 2
        balances.getValue(recent).oldestReceivedOn shouldBe today.minusDays(10)
        balances.getValue(recent).oldestAgeDays shouldBe 10
        balances.getValue(recent).stale shouldBe false
        balances.getValue(overdue).stale shouldBe true
        accruals.balances().indexOfFirst { it.vendorId == overdue } shouldBe 0
        accruals.open(recent, PageQuery.of()).items.map { it.receipt.receivedOn } shouldBe
            listOf(today.minusDays(10), today.minusDays(2))
    }

    @Test
    fun `an invoice that agrees with its receipts matches, relieves the accrual, and is announced`(scenario: Scenario) {
        val vendorId = VendorId.generate(clock)
        val orderId = PurchaseOrderId.generate(clock)
        receive(vendorId, orderId, cases = 10, caseCost = "18.00")
        var invoiceId: InvoiceId? = null

        scenario
            .stimulate(
                Runnable { invoiceId = enterVendorInvoice.handle(details(vendorId, orderId, "INV-1", 6, "18.25")) },
            ).andWaitForEventOfType(InvoiceMatched::class.java)
            .matching { it.invoice.purchaseOrderId == orderId }
            .toArriveAndVerify { event ->
                event.invoiceAmount shouldBe BigDecimal("109.5000")
                event.accruedAmount shouldBe BigDecimal("108.0000")
                event.priceVariance shouldBe BigDecimal("1.5000")
            }

        val invoice = invoices.find(requireNotNull(invoiceId))
        invoice.status shouldBe InvoiceStatus.Matched(today)
        invoice.enteredBy shouldBe "clerk"
        accruals
            .open(vendorId, PageQuery.of())
            .items
            .single()
            .openAmount shouldBe usd("72.00")
        accruals.balances().first { it.vendorId == vendorId }.openAmount shouldBe usd("72.00")
        shouldThrow<InvoiceAlreadyEntered> {
            enterVendorInvoice.handle(
                details(vendorId, orderId, "INV-1", 1, "18.00"),
            )
        }
        shouldThrow<InvoiceAlreadyMatched> { rematchVendorInvoice.handle(invoice.id) }
    }

    @Test
    fun `an invoice ahead of its goods waits as an exception and matches once they arrive`(scenario: Scenario) {
        val vendorId = VendorId.generate(clock)
        val orderId = PurchaseOrderId.generate(clock)
        var invoiceId: InvoiceId? = null

        scenario
            .stimulate(
                Runnable { invoiceId = enterVendorInvoice.handle(details(vendorId, orderId, "INV-2", 4, "18.00")) },
            ).andWaitForEventOfType(InvoiceMatchException::class.java)
            .matching { it.invoice.purchaseOrderId == orderId }
            .toArriveAndVerify { event -> event.exceptions.single().reason shouldBe "NOTHING_RECEIVED" }

        val id = requireNotNull(invoiceId)
        invoices
            .find(id)
            .status
            .shouldBeInstanceOf<InvoiceStatus.Exception>()
            .exceptions
            .single()
            .reason shouldBe
            MatchExceptionReason.NOTHING_RECEIVED
        invoices.list(exceptionsOnly = true, PageQuery.of()).items.map { it.id } shouldContain id

        receive(vendorId, orderId, cases = 4, caseCost = "18.00")
        rematchVendorInvoice.handle(id)

        invoices.find(id).status shouldBe InvoiceStatus.Matched(today)
        invoices.list(exceptionsOnly = true, PageQuery.of()).items.map { it.id } shouldNotContain id
        invoices.list(exceptionsOnly = false, PageQuery.of()).items.map { it.id } shouldContain id
        accruals.open(vendorId, PageQuery.of()).total shouldBe 0
        shouldThrow<ResourceNotFound> { invoices.find(InvoiceId.generate(clock)) }
    }

    @Test
    fun `a matched invoice releases a voucher on the vendor's terms`(scenario: Scenario) {
        val vendorId = VendorId.generate(clock)
        val orderId = PurchaseOrderId.generate(clock)
        knownVendor(scenario, vendorId, PaymentTermsFacts(30, BigDecimal("2.0000"), 10))
        receive(vendorId, orderId, cases = 10, caseCost = "18.00")

        scenario
            .stimulate(Runnable { enterVendorInvoice.handle(details(vendorId, orderId, "INV-4", 10, "18.00")) })
            .andWaitForEventOfType(PayableVoucherReleased::class.java)
            .matching { it.invoice.purchaseOrderId == orderId }
            .toArriveAndVerify { event ->
                event.amount shouldBe BigDecimal("180.0000")
                event.dueOn shouldBe today.plusDays(30).value
                event.overrideApprovedBy shouldBe null
            }

        val voucher = vouchers.list(vendorId, null, PageQuery.of()).items.single()
        voucher.amount shouldBe usd("180.00")
        voucher.terms.discount shouldBe VoucherDiscount(Percentage.of("2"), today.plusDays(10))
        voucher.status shouldBe VoucherStatus.Released
        vouchers.find(voucher.id).number shouldBe voucher.number
        vouchers.list(vendorId, "PAID", PageQuery.of()).total shouldBe 0
        shouldThrow<ResourceNotFound> { vouchers.find(VoucherId.generate(clock)) }
    }

    @Test
    fun `a vendor's changed terms apply to invoices dated from the change, and an unknown vendor is net 30`(
        scenario: Scenario,
    ) {
        val vendorId = VendorId.generate(clock)
        knownVendor(scenario, vendorId, PaymentTermsFacts(15, null, null), effectiveFrom = today.minusDays(20))
        val change =
            VendorTermsChanged(
                EventId.generate(clock),
                clock.instant(),
                today.minusDays(5),
                vendorId,
                PaymentTermsFacts(45, null, null),
            )
        scenario
            .publish(change)
            .andWaitForStateChange { Optional.ofNullable(terms(vendorId, today)).filter { it.netDays == 45 } }
            .andVerify { it.isPresent shouldBe true }

        terms(vendorId, today.minusDays(6))?.netDays shouldBe 15
        // An invoice dated before the vendor was set up uses the vendor's first terms.
        terms(vendorId, today.minusDays(40))?.netDays shouldBe 15

        val stranger = VendorId.generate(clock)
        val orderId = PurchaseOrderId.generate(clock)
        receive(stranger, orderId, cases = 1, caseCost = "5.00")
        enterVendorInvoice.handle(details(stranger, orderId, "INV-5", 1, "5.00"))
        vouchers
            .list(stranger, null, PageQuery.of())
            .items
            .single()
            .terms.dueOn shouldBe today.plusDays(30)
    }

    @Test
    fun `a manager who did not enter the invoice overrides a price exception and the voucher is released`(
        scenario: Scenario,
    ) {
        val vendorId = VendorId.generate(clock)
        val orderId = PurchaseOrderId.generate(clock)
        receive(vendorId, orderId, cases = 10, caseCost = "18.00")
        val id = enterVendorInvoice.handle(details(vendorId, orderId, "INV-6", 10, "20.00"))
        invoices.find(id).status.shouldBeInstanceOf<InvoiceStatus.Exception>()
        vouchers.list(vendorId, null, PageQuery.of()).total shouldBe 0

        // The clerk who entered it may not override it, whatever role they hold.
        shouldThrow<AccessDeniedException> { overrideMatchException.handle(id, "Agreed") }
        actingAs("clerk", Roles.ACCOUNTING_MANAGER)
        shouldThrow<OverrideByInvoiceCreator> { overrideMatchException.handle(id, "Agreed") }

        actingAs("manager", Roles.ACCOUNTING_MANAGER)
        scenario
            .stimulate(Runnable { overrideMatchException.handle(id, "Cost increase agreed with the buyer") })
            .andWaitForEventOfType(PayableVoucherReleased::class.java)
            .matching { it.invoice.invoiceId == id }
            .toArriveAndVerify { event ->
                event.amount shouldBe BigDecimal("200.0000")
                event.overrideApprovedBy shouldBe "manager"
            }

        invoices.find(id).status shouldBe
            InvoiceStatus.Matched(today, MatchOverride.of("manager", "Cost increase agreed with the buyer"))
        accruals.open(vendorId, PageQuery.of()).total shouldBe 0
        shouldThrow<InvoiceAlreadyMatched> { overrideMatchException.handle(id, "Again") }
        shouldThrow<ResourceNotFound> { overrideMatchException.handle(InvoiceId.generate(clock), "Missing") }
    }

    @Test
    fun `an override does not release an invoice for cases that were not received`() {
        val vendorId = VendorId.generate(clock)
        val orderId = PurchaseOrderId.generate(clock)
        receive(vendorId, orderId, cases = 4, caseCost = "18.00")
        val id = enterVendorInvoice.handle(details(vendorId, orderId, "INV-7", 5, "18.00"))

        actingAs("manager", Roles.ACCOUNTING_MANAGER)
        shouldThrow<OverrideDoesNotResolve> { overrideMatchException.handle(id, "Pay it anyway") }

        invoices.find(id).status.shouldBeInstanceOf<InvoiceStatus.Exception>()
        accruals
            .open(vendorId, PageQuery.of())
            .items
            .single()
            .openAmount shouldBe usd("72.00")
        vouchers.list(vendorId, null, PageQuery.of()).total shouldBe 0
    }

    @Test
    fun `a payment run pays what is due, takes the discount, and is approved by someone else`(scenario: Scenario) {
        val vendorId = VendorId.generate(clock)
        knownVendor(scenario, vendorId, PaymentTermsFacts(30, BigDecimal("2.0000"), 10))
        val due = vouchered(vendorId, "INV-8", cases = 10, caseCost = "10.00", invoiceDate = today.minusDays(30))
        val discounted = vouchered(vendorId, "INV-9", cases = 10, caseCost = "20.00", invoiceDate = today.minusDays(5))
        val later = vouchered(vendorId, "INV-10", cases = 1, caseCost = "5.00", invoiceDate = today)

        val runId = proposePaymentRun.handle(PaymentWindow.of(today, today.plusDays(7)))

        val proposed = payments.run(runId)
        proposed.run.status shouldBe PaymentRunStatus.Proposed
        proposed.run.proposedBy shouldBe "clerk"
        proposed.payments shouldBe emptyList()
        val lines = proposed.run.lines.filter { it.vendorId == vendorId }
        lines.map { it.voucherId } shouldBe listOf(due, discounted)
        lines.map { it.discount } shouldBe listOf(usd("0.00"), usd("4.00"))
        vouchers.find(due).status shouldBe VoucherStatus.Scheduled(runId)
        vouchers.find(later).status shouldBe VoucherStatus.Released
        payments
            .runs(PageQuery.of())
            .items
            .first()
            .id shouldBe runId

        // The clerk proposes; only a manager approves, and not a manager who proposed the run.
        shouldThrow<AccessDeniedException> { approvePaymentRun.handle(runId) }
        actingAs("clerk", Roles.ACCOUNTING_MANAGER)
        shouldThrow<PaymentRunApprovedByProposer> { approvePaymentRun.handle(runId) }

        actingAs("manager", Roles.ACCOUNTING_MANAGER)
        scenario
            .stimulate(Runnable { approvePaymentRun.handle(runId) })
            .andWaitForEventOfType(PaymentIssued::class.java)
            .matching { it.vendorId == vendorId }
            .toArriveAndVerify { event ->
                event.grossAmount shouldBe BigDecimal("300.0000")
                event.discountAmount shouldBe BigDecimal("4.0000")
                event.netAmount shouldBe BigDecimal("296.0000")
                event.businessDate shouldBe today
                event.vouchers.size shouldBe 2
            }

        val approved = payments.run(runId)
        approved.run.status shouldBe PaymentRunStatus.Approved("manager", today)
        val payment = approved.payments.single { it.vendorId == vendorId }
        payment.net shouldBe usd("296.00")
        payments.payment(payment.id).vouchers.map { it.voucherId } shouldBe listOf(due, discounted)
        vouchers.find(due).status shouldBe VoucherStatus.Paid(runId, payment.id, today)
        vouchers.list(vendorId, "PAID", PageQuery.of()).total shouldBe 2
        shouldThrow<PaymentRunNotProposed> { approvePaymentRun.handle(runId) }
        shouldThrow<PaymentRunNotProposed> { cancelPaymentRun.handle(runId) }
        shouldThrow<ResourceNotFound> { payments.payment(PaymentId.generate(clock)) }
        shouldThrow<ResourceNotFound> { payments.run(PaymentRunId.generate(clock)) }
        shouldThrow<ResourceNotFound> { approvePaymentRun.handle(PaymentRunId.generate(clock)) }
    }

    @Test
    fun `a cancelled payment run gives its vouchers back for the next run`() {
        val vendorId = VendorId.generate(clock)
        val voucherId = vouchered(vendorId, "INV-11", cases = 2, caseCost = "7.00", invoiceDate = today.minusDays(30))
        val runId = proposePaymentRun.handle(PaymentWindow.of(today, today))
        // Everything payable is now reserved, so a second run has nothing to pay.
        shouldThrow<NothingToPay> { proposePaymentRun.handle(PaymentWindow.of(today, today)) }

        cancelPaymentRun.handle(runId)

        payments.run(runId).run.status shouldBe PaymentRunStatus.Cancelled("clerk", today)
        vouchers.find(voucherId).status shouldBe VoucherStatus.Released
        val next = proposePaymentRun.handle(PaymentWindow.of(today, today))
        vouchers.find(voucherId).status shouldBe VoucherStatus.Scheduled(next)
        cancelPaymentRun.handle(next)
        shouldThrow<ResourceNotFound> { cancelPaymentRun.handle(PaymentRunId.generate(clock)) }
        shouldThrow<PaymentDateInPast> { proposePaymentRun.handle(PaymentWindow.of(today.minusDays(1), today)) }
    }

    @Test
    fun `a direct delivery is accrued for what came and credited for what the driver took back`(scenario: Scenario) {
        val vendorId = VendorId.generate(clock)
        val event =
            DirectDeliveryReceived(
                eventId = EventId.generate(clock),
                occurredAt = clock.instant(),
                businessDate = today,
                delivery =
                    DirectDeliveryFacts(
                        DirectDeliveryId.generate(clock),
                        "100077",
                        vendorId,
                        StoreId.generate(clock),
                        "T-1",
                    ),
                currency = "USD",
                lines =
                    listOf(
                        DirectDeliveryLine(1, ItemId.generate(clock), 20, 3, BigDecimal("1.2500")),
                        DirectDeliveryLine(2, ItemId.generate(clock), 0, 5, BigDecimal("0.8000")),
                    ),
            )

        scenario
            .publish(event)
            .andWaitForStateChange { directDeliveries.credits(vendorId, PageQuery.of()).total == 1L }
            .andVerify { it shouldBe true }

        val accrual = directDeliveries.accruals(vendorId, PageQuery.of()).items.single()
        accrual.amount shouldBe usd("25.00")
        accrual.ticket.ticketNumber shouldBe "T-1"
        accrual.ticket.receivedOn shouldBe today
        accrual.lines.single().units shouldBe 20
        val credit = directDeliveries.credits(vendorId, PageQuery.of()).items.single()
        credit.amount shouldBe usd("7.75")
        credit.lines.map { it.units } shouldBe listOf(3, 5)
        credit.ticket.deliveryId shouldBe event.delivery.deliveryId

        // The same delivery arriving again accrues and credits nothing more.
        recordDirectDelivery.handle(
            RecordDirectDeliveryCommand(accrual.ticket, accrual.lines, credit.lines),
        )
        directDeliveries.accruals(vendorId, PageQuery.of()).total shouldBe 1
        directDeliveries.credits(vendorId, PageQuery.of()).total shouldBe 1
    }

    @Test
    @WithMockUser(authorities = [Roles.BUYER])
    fun `only accounting enters and matches invoices`() {
        val details = details(VendorId.generate(clock), PurchaseOrderId.generate(clock), "INV-3", 1, "1.00")

        shouldThrow<AccessDeniedException> { enterVendorInvoice.handle(details) }
        shouldThrow<AccessDeniedException> { rematchVendorInvoice.handle(InvoiceId.generate(clock)) }
        shouldThrow<AccessDeniedException> { overrideMatchException.handle(InvoiceId.generate(clock), "No") }
        shouldThrow<AccessDeniedException> { proposePaymentRun.handle(PaymentWindow.of(today, today)) }
        shouldThrow<AccessDeniedException> { cancelPaymentRun.handle(PaymentRunId.generate(clock)) }
    }

    /** Announces a vendor with [terms] and waits until payables has them. */
    private fun knownVendor(
        scenario: Scenario,
        vendorId: VendorId,
        terms: PaymentTermsFacts,
        effectiveFrom: BusinessDate = today.minusDays(60),
    ) {
        val created =
            VendorCreated(EventId.generate(clock), clock.instant(), effectiveFrom, vendorId, "V1", "Vendor", terms)
        scenario
            .publish(created)
            .andWaitForStateChange { Optional.ofNullable(terms(vendorId, effectiveFrom)) }
            .andVerify { it.isPresent shouldBe true }
    }

    private fun terms(
        vendorId: VendorId,
        invoiceDate: BusinessDate,
    ): VendorTerms? = vendorTerms.findFor(vendorId, invoiceDate)

    /** Receives, invoices, and matches an order, and returns the voucher released for it. */
    private fun vouchered(
        vendorId: VendorId,
        number: String,
        cases: Int,
        caseCost: String,
        invoiceDate: BusinessDate,
    ): VoucherId {
        val orderId = PurchaseOrderId.generate(clock)
        receive(vendorId, orderId, cases, caseCost)
        val invoiceId =
            enterVendorInvoice.handle(
                InvoiceDetails(
                    vendorId,
                    VendorInvoiceNumber.of(number),
                    invoiceDate,
                    orderId,
                    listOf(InvoiceLine.of(1, cases, usd(caseCost))),
                ),
            )
        return vouchers
            .list(vendorId, null, PageQuery.of())
            .items
            .single { it.invoice.invoiceId == invoiceId }
            .id
    }

    private fun actingAs(
        user: String,
        vararg roles: String,
    ) {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(user, "n/a", roles.map(::SimpleGrantedAuthority))
    }

    private fun details(
        vendorId: VendorId,
        orderId: PurchaseOrderId,
        number: String,
        cases: Int,
        caseCost: String,
    ) = InvoiceDetails(
        vendorId,
        VendorInvoiceNumber.of(number),
        today,
        orderId,
        listOf(InvoiceLine.of(1, cases, usd(caseCost))),
    )

    private fun receive(
        vendorId: VendorId,
        orderId: PurchaseOrderId,
        cases: Int,
        caseCost: String,
    ) {
        recordAccrual.handle(
            RecordAccrualCommand(
                AccruedReceipt(
                    ReceiptId.generate(clock),
                    "300003",
                    orderId,
                    "200003",
                    vendorId,
                    WarehouseId.generate(clock),
                    today,
                ),
                listOf(AccrualLine.of(1, ItemId.generate(clock), cases, usd(caseCost))),
            ),
        )
    }

    private fun accrue(
        vendorId: VendorId,
        receivedOn: BusinessDate,
        amount: String,
    ) {
        recordAccrual.handle(
            RecordAccrualCommand(
                accruedReceipt(vendorId, ReceiptId.generate(clock), receivedOn),
                listOf(AccrualLine.of(1, ItemId.generate(clock), 1, usd(amount))),
            ),
        )
    }

    private fun accruedReceipt(
        vendorId: VendorId,
        receiptId: ReceiptId,
        receivedOn: BusinessDate,
    ) = AccruedReceipt(
        receiptId,
        "300002",
        PurchaseOrderId.generate(clock),
        "200002",
        vendorId,
        WarehouseId.generate(clock),
        receivedOn,
    )

    private fun receiptFacts(
        vendorId: VendorId,
        number: String,
    ) = ReceiptFacts(
        ReceiptId.generate(clock),
        number,
        PurchaseOrderId.generate(clock),
        "200001",
        vendorId,
        WarehouseId.generate(clock),
    )

    private fun usd(amount: String) = Money.of(amount, "USD")
}
