package com.retailplatform.payables

import com.retailplatform.TestcontainersConfiguration
import com.retailplatform.payables.api.events.DirectDeliveryInvoiceMatchException
import com.retailplatform.payables.api.events.DirectDeliveryInvoiceMatched
import com.retailplatform.payables.api.events.PayableVoucherReleased
import com.retailplatform.payables.api.events.PaymentIssued
import com.retailplatform.payables.application.ApprovePaymentRun
import com.retailplatform.payables.application.CancelPaymentRun
import com.retailplatform.payables.application.DirectDeliveryInvoiceQueries
import com.retailplatform.payables.application.DirectDeliveryPayablesQueries
import com.retailplatform.payables.application.EnterDirectDeliveryInvoice
import com.retailplatform.payables.application.OverrideDirectDeliveryException
import com.retailplatform.payables.application.PaymentQueries
import com.retailplatform.payables.application.ProposePaymentRun
import com.retailplatform.payables.application.RecordDirectDelivery
import com.retailplatform.payables.application.RecordDirectDeliveryCommand
import com.retailplatform.payables.application.RematchDirectDeliveryInvoice
import com.retailplatform.payables.application.VoucherQueries
import com.retailplatform.payables.domain.BilledTicket
import com.retailplatform.payables.domain.DeliveredTicket
import com.retailplatform.payables.domain.InvoiceAlreadyEntered
import com.retailplatform.payables.domain.InvoiceAlreadyMatched
import com.retailplatform.payables.domain.MatchOverride
import com.retailplatform.payables.domain.OverrideByInvoiceCreator
import com.retailplatform.payables.domain.OverrideDoesNotResolve
import com.retailplatform.payables.domain.PaymentWindow
import com.retailplatform.payables.domain.TicketAlreadyInvoiced
import com.retailplatform.payables.domain.TicketAmountLine
import com.retailplatform.payables.domain.TicketExceptionReason
import com.retailplatform.payables.domain.TicketInvoiceDetails
import com.retailplatform.payables.domain.TicketInvoiceStatus
import com.retailplatform.payables.domain.VendorCreditId
import com.retailplatform.payables.domain.VendorCreditStatus
import com.retailplatform.payables.domain.VendorInvoiceNumber
import com.retailplatform.platform.security.Roles
import com.retailplatform.receiving.api.events.DirectDeliveryFacts
import com.retailplatform.receiving.api.events.DirectDeliveryLine
import com.retailplatform.receiving.api.events.DirectDeliveryReceived
import com.retailplatform.shared.BusinessDate
import com.retailplatform.shared.DirectDeliveryId
import com.retailplatform.shared.EventId
import com.retailplatform.shared.InvoiceId
import com.retailplatform.shared.ItemId
import com.retailplatform.shared.Money
import com.retailplatform.shared.PageQuery
import com.retailplatform.shared.ResourceNotFound
import com.retailplatform.shared.StoreId
import com.retailplatform.shared.VendorId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContain
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

/**
 * Direct store deliveries in payables: the accrual and credit per ticket, the two-way match, and credits in
 * payments.
 */
@ApplicationModuleTest
@Import(TestcontainersConfiguration::class)
@WithMockUser(username = "clerk", authorities = [Roles.ACCOUNTING_CLERK])
class DirectDeliveryPayablesModuleTest(
    @Autowired private val recordDirectDelivery: RecordDirectDelivery,
    @Autowired private val directDeliveries: DirectDeliveryPayablesQueries,
    @Autowired private val enterDirectDeliveryInvoice: EnterDirectDeliveryInvoice,
    @Autowired private val rematchDirectDeliveryInvoice: RematchDirectDeliveryInvoice,
    @Autowired private val overrideDirectDeliveryException: OverrideDirectDeliveryException,
    @Autowired private val directDeliveryInvoices: DirectDeliveryInvoiceQueries,
    @Autowired private val vouchers: VoucherQueries,
    @Autowired private val proposePaymentRun: ProposePaymentRun,
    @Autowired private val approvePaymentRun: ApprovePaymentRun,
    @Autowired private val cancelPaymentRun: CancelPaymentRun,
    @Autowired private val payments: PaymentQueries,
    @Autowired private val clock: Clock,
) {
    private val today get() = BusinessDate.today(clock)

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
    fun `a direct delivery invoice matches its ticket, relieves the accrual, and releases a voucher`(
        scenario: Scenario,
    ) {
        val vendorId = VendorId.generate(clock)
        val storeId = StoreId.generate(clock)
        val cola = ItemId.generate(clock)
        delivered(vendorId, storeId, "T-20", TicketAmountLine.of(cola, 20, usd("1.25")))
        var invoiceId: InvoiceId? = null

        scenario
            .stimulate(
                Runnable {
                    invoiceId =
                        enterDirectDeliveryInvoice.handle(
                            ticketInvoice(vendorId, storeId, "T-20", "DSD-20", cola, 20, "1.26"),
                        )
                },
            ).andWaitForEventOfType(DirectDeliveryInvoiceMatched::class.java)
            .matching { it.invoice.vendorId == vendorId }
            .toArriveAndVerify { event ->
                event.invoiceAmount shouldBe BigDecimal("25.2000")
                event.accruedAmount shouldBe BigDecimal("25.0000")
                event.costVariance shouldBe BigDecimal("0.2000")
            }

        val invoice = directDeliveryInvoices.find(requireNotNull(invoiceId))
        invoice.status shouldBe TicketInvoiceStatus.Matched(today)
        invoice.enteredBy shouldBe "clerk"
        directDeliveries
            .accruals(vendorId, PageQuery.of())
            .items
            .single()
            .relievedOn shouldBe today
        val voucher = vouchers.list(vendorId, null, PageQuery.of()).items.single()
        voucher.amount shouldBe usd("25.20")
        voucher.invoice.purchaseOrderId shouldBe null
        voucher.invoice.invoiceId shouldBe invoice.id

        shouldThrow<TicketAlreadyInvoiced> {
            enterDirectDeliveryInvoice.handle(ticketInvoice(vendorId, storeId, "T-20", "DSD-21", cola, 20, "1.25"))
        }
        shouldThrow<InvoiceAlreadyEntered> {
            enterDirectDeliveryInvoice.handle(ticketInvoice(vendorId, storeId, "T-99", "DSD-20", cola, 20, "1.25"))
        }
        shouldThrow<InvoiceAlreadyMatched> { rematchDirectDeliveryInvoice.handle(invoice.id) }
        shouldThrow<ResourceNotFound> { directDeliveryInvoices.find(InvoiceId.generate(clock)) }
    }

    @Test
    fun `a direct delivery invoice ahead of its ticket, or billing other quantities, waits for the buyer`(
        scenario: Scenario,
    ) {
        val vendorId = VendorId.generate(clock)
        val storeId = StoreId.generate(clock)
        val cola = ItemId.generate(clock)
        var invoiceId: InvoiceId? = null

        scenario
            .stimulate(
                Runnable {
                    invoiceId =
                        enterDirectDeliveryInvoice.handle(
                            ticketInvoice(vendorId, storeId, "T-30", "DSD-30", cola, 20, "1.25"),
                        )
                },
            ).andWaitForEventOfType(DirectDeliveryInvoiceMatchException::class.java)
            .matching { it.invoice.vendorId == vendorId }
            .toArriveAndVerify { event -> event.exceptions.single().reason shouldBe "TICKET_NOT_RECEIVED" }

        val id = requireNotNull(invoiceId)
        directDeliveryInvoices.list(exceptionsOnly = true, PageQuery.of()).items.map { it.id } shouldContain id

        // The store checks the ticket in, but for 18 units: the invoice still does not agree, and an override
        // cannot make it.
        delivered(vendorId, storeId, "T-30", TicketAmountLine.of(cola, 18, usd("1.25")))
        rematchDirectDeliveryInvoice.handle(id)
        directDeliveryInvoices
            .find(id)
            .status
            .shouldBeInstanceOf<TicketInvoiceStatus.Exception>()
            .exceptions
            .single()
            .reason shouldBe TicketExceptionReason.QUANTITY_DIFFERS
        actingAs("approver", Roles.BUYER_APPROVER)
        shouldThrow<OverrideDoesNotResolve> { overrideDirectDeliveryException.handle(id, "Pay it") }
        directDeliveries
            .accruals(vendorId, PageQuery.of())
            .items
            .single()
            .isOpen shouldBe true
        vouchers.list(vendorId, null, PageQuery.of()).total shouldBe 0
    }

    @Test
    fun `a buyer who may approve, and did not enter the invoice, overrides a direct delivery price exception`(
        scenario: Scenario,
    ) {
        val vendorId = VendorId.generate(clock)
        val storeId = StoreId.generate(clock)
        val cola = ItemId.generate(clock)
        delivered(vendorId, storeId, "T-40", TicketAmountLine.of(cola, 20, usd("1.25")))
        val id = enterDirectDeliveryInvoice.handle(ticketInvoice(vendorId, storeId, "T-40", "DSD-40", cola, 20, "1.50"))
        directDeliveryInvoices.find(id).status.shouldBeInstanceOf<TicketInvoiceStatus.Exception>()

        // Accounting enters invoices but does not override them; nor does the person who entered this one.
        shouldThrow<AccessDeniedException> { overrideDirectDeliveryException.handle(id, "Agreed") }
        actingAs("clerk", Roles.BUYER_APPROVER)
        shouldThrow<OverrideByInvoiceCreator> { overrideDirectDeliveryException.handle(id, "Agreed") }

        actingAs("approver", Roles.BUYER_APPROVER)
        scenario
            .stimulate(Runnable { overrideDirectDeliveryException.handle(id, "New price list not loaded yet") })
            .andWaitForEventOfType(PayableVoucherReleased::class.java)
            .matching { it.invoice.invoiceId == id }
            .toArriveAndVerify { event ->
                event.amount shouldBe BigDecimal("30.0000")
                event.overrideApprovedBy shouldBe "approver"
                event.invoice.purchaseOrderId shouldBe null
            }

        directDeliveryInvoices.find(id).status shouldBe
            TicketInvoiceStatus.Matched(today, MatchOverride.of("approver", "New price list not loaded yet"))
        shouldThrow<ResourceNotFound> { overrideDirectDeliveryException.handle(InvoiceId.generate(clock), "Missing") }
    }

    @Test
    fun `a payment run deducts the vendor's open credit from its payment`(scenario: Scenario) {
        val vendorId = VendorId.generate(clock)
        val creditId = invoicedWithCredit(vendorId, "T-50", "DSD-50")

        val runId = proposePaymentRun.handle(PaymentWindow.of(today, today))
        credit(vendorId).status shouldBe VendorCreditStatus.Scheduled(runId)
        actingAs("manager", Roles.ACCOUNTING_MANAGER)
        scenario
            .stimulate(Runnable { approvePaymentRun.handle(runId) })
            .andWaitForEventOfType(PaymentIssued::class.java)
            .matching { it.vendorId == vendorId }
            .toArriveAndVerify { event ->
                event.grossAmount shouldBe BigDecimal("25.0000")
                event.creditAmount shouldBe BigDecimal("5.0000")
                event.netAmount shouldBe BigDecimal("20.0000")
            }

        val payment = payments.run(runId).payments.single { it.vendorId == vendorId }
        payment.credit shouldBe usd("5.00")
        payment.net shouldBe usd("20.00")
        payments
            .payment(payment.id)
            .credits
            .single()
            .creditId shouldBe creditId
        credit(vendorId).status shouldBe VendorCreditStatus.Applied(runId, payment.id, today)
    }

    @Test
    fun `a cancelled payment run gives the vendor's credit back`() {
        val vendorId = VendorId.generate(clock)
        val creditId = invoicedWithCredit(vendorId, "T-60", "DSD-60")

        val runId = proposePaymentRun.handle(PaymentWindow.of(today, today))
        payments
            .run(runId)
            .run.credits
            .map { it.creditId } shouldContain creditId
        cancelPaymentRun.handle(runId)

        credit(vendorId).status shouldBe VendorCreditStatus.Open
    }

    @Test
    @WithMockUser(authorities = [Roles.BUYER])
    fun `only accounting enters and matches direct delivery invoices`() {
        val details =
            ticketInvoice(
                VendorId.generate(clock),
                StoreId.generate(clock),
                "T-1",
                "DSD-1",
                ItemId.generate(clock),
                1,
                "1.00",
            )

        shouldThrow<AccessDeniedException> { enterDirectDeliveryInvoice.handle(details) }
        shouldThrow<AccessDeniedException> { rematchDirectDeliveryInvoice.handle(InvoiceId.generate(clock)) }
    }

    /** A ticket with 20 units delivered and 4 returned at 1.25, invoiced and due; returns the credit's id. */
    private fun invoicedWithCredit(
        vendorId: VendorId,
        ticket: String,
        number: String,
    ): VendorCreditId {
        val storeId = StoreId.generate(clock)
        val cola = ItemId.generate(clock)
        delivered(
            vendorId,
            storeId,
            ticket,
            TicketAmountLine.of(cola, 20, usd("1.25")),
            returned = listOf(TicketAmountLine.of(cola, 4, usd("1.25"))),
        )
        enterDirectDeliveryInvoice.handle(
            ticketInvoice(vendorId, storeId, ticket, number, cola, 20, "1.25", invoiceDate = today.minusDays(30)),
        )
        return credit(vendorId).id
    }

    private fun credit(vendorId: VendorId) = directDeliveries.credits(vendorId, PageQuery.of()).items.single()

    /** Records the store's check-in of a ticket, as the listener does for DirectDeliveryReceived. */
    private fun delivered(
        vendorId: VendorId,
        storeId: StoreId,
        ticket: String,
        vararg delivered: TicketAmountLine,
        returned: List<TicketAmountLine> = emptyList(),
    ) {
        recordDirectDelivery.handle(
            RecordDirectDeliveryCommand(
                DeliveredTicket(DirectDeliveryId.generate(clock), "100090", vendorId, storeId, ticket, today),
                delivered.toList(),
                returned,
            ),
        )
    }

    private fun ticketInvoice(
        vendorId: VendorId,
        storeId: StoreId,
        ticket: String,
        number: String,
        itemId: ItemId,
        units: Int,
        unitCost: String,
        invoiceDate: BusinessDate = today,
    ) = TicketInvoiceDetails(
        BilledTicket(vendorId, storeId, ticket),
        VendorInvoiceNumber.of(number),
        invoiceDate,
        listOf(TicketAmountLine.of(itemId, units, usd(unitCost))),
    )

    private fun actingAs(
        user: String,
        vararg roles: String,
    ) {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(user, "n/a", roles.map(::SimpleGrantedAuthority))
    }

    private fun usd(amount: String) = Money.of(amount, "USD")
}
