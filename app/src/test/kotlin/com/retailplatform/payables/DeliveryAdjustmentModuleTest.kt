package com.retailplatform.payables

import com.retailplatform.TestcontainersConfiguration
import com.retailplatform.payables.api.events.DirectDeliveryAdjustmentResolved
import com.retailplatform.payables.application.DirectDeliveryPayablesQueries
import com.retailplatform.payables.application.EnterDirectDeliveryInvoice
import com.retailplatform.payables.application.PayDeliveryAdjustment
import com.retailplatform.payables.application.RecordDeliveryCorrection
import com.retailplatform.payables.application.RecordDeliveryCorrectionCommand
import com.retailplatform.payables.application.RecordDirectDelivery
import com.retailplatform.payables.application.RecordDirectDeliveryCommand
import com.retailplatform.payables.application.VendorDocument
import com.retailplatform.payables.application.VoucherQueries
import com.retailplatform.payables.application.WriteOffDeliveryAdjustment
import com.retailplatform.payables.domain.AdjustmentAlreadyResolved
import com.retailplatform.payables.domain.AdjustmentResolution
import com.retailplatform.payables.domain.BilledTicket
import com.retailplatform.payables.domain.DeliveredTicket
import com.retailplatform.payables.domain.DeliveryAdjustmentId
import com.retailplatform.payables.domain.InvoiceAlreadyEntered
import com.retailplatform.payables.domain.MatchOverride
import com.retailplatform.payables.domain.TicketAmountLine
import com.retailplatform.payables.domain.TicketInvoiceDetails
import com.retailplatform.payables.domain.TicketUnitChange
import com.retailplatform.payables.domain.VendorInvoiceNumber
import com.retailplatform.platform.security.Roles
import com.retailplatform.shared.BusinessDate
import com.retailplatform.shared.DirectDeliveryId
import com.retailplatform.shared.ItemId
import com.retailplatform.shared.Money
import com.retailplatform.shared.PageQuery
import com.retailplatform.shared.ResourceNotFound
import com.retailplatform.shared.StoreId
import com.retailplatform.shared.VendorId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
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

/** Settling the adjustments that late corrections to direct delivery receipts leave for accounting. */
@ApplicationModuleTest
@Import(TestcontainersConfiguration::class)
@WithMockUser(username = "clerk", authorities = [Roles.ACCOUNTING_CLERK])
class DeliveryAdjustmentModuleTest(
    @Autowired private val recordDirectDelivery: RecordDirectDelivery,
    @Autowired private val recordDeliveryCorrection: RecordDeliveryCorrection,
    @Autowired private val enterDirectDeliveryInvoice: EnterDirectDeliveryInvoice,
    @Autowired private val directDeliveries: DirectDeliveryPayablesQueries,
    @Autowired private val payAdjustment: PayDeliveryAdjustment,
    @Autowired private val writeOffAdjustment: WriteOffDeliveryAdjustment,
    @Autowired private val vouchers: VoucherQueries,
    @Autowired private val clock: Clock,
) {
    private val today get() = BusinessDate.today(clock)

    @Test
    fun `an accounting manager pays an adjustment against the vendor's document or writes it off`(scenario: Scenario) {
        val vendorId = VendorId.generate(clock)
        val owed = adjustmentAfterInvoice(vendorId, "T-85", "DSD-85")
        val notOwed = adjustmentAfterInvoice(vendorId, "T-86", "DSD-86")
        val document = VendorDocument(VendorInvoiceNumber.of("DM-85"), today.minusDays(3))

        // A clerk does not settle adjustments, and a document already entered for the vendor is not paid twice.
        shouldThrow<AccessDeniedException> { payAdjustment.handle(owed, document, "Vendor billed the extra units") }
        shouldThrow<AccessDeniedException> { writeOffAdjustment.handle(notOwed, "Vendor will not bill") }
        actingAs("manager", Roles.ACCOUNTING_MANAGER)
        shouldThrow<InvoiceAlreadyEntered> {
            payAdjustment.handle(owed, document.copy(reference = VendorInvoiceNumber.of("DSD-85")), "Same number")
        }

        scenario
            .stimulate(Runnable { payAdjustment.handle(owed, document, "Vendor billed the extra units") })
            .andWaitForEventOfType(DirectDeliveryAdjustmentResolved::class.java)
            .matching { it.adjustmentId == owed.value }
            .toArriveAndVerify { event ->
                event.resolution shouldBe "PAID"
                event.amount shouldBe BigDecimal("4.0000")
                event.vendorReference shouldBe "DM-85"
            }
        directDeliveries.adjustment(owed).resolution shouldBe
            AdjustmentResolution.Paid(
                today,
                MatchOverride.of("manager", "Vendor billed the extra units"),
                VendorInvoiceNumber.of("DM-85"),
                today.minusDays(3),
            )
        val voucher = vouchers.list(vendorId, null, PageQuery.of()).items.single { it.amount == usd("4.00") }
        voucher.invoice.vendorInvoiceNumber shouldBe VendorInvoiceNumber.of("DM-85")
        voucher.invoice.invoiceDate shouldBe today.minusDays(3)

        // The same document does not pay a second adjustment, and is not entered as an invoice either.
        shouldThrow<InvoiceAlreadyEntered> { payAdjustment.handle(notOwed, document, "Again") }
        shouldThrow<AdjustmentAlreadyResolved> { writeOffAdjustment.handle(owed, "Changed my mind") }
        shouldThrow<ResourceNotFound> { payAdjustment.handle(DeliveryAdjustmentId.generate(clock), document, "None") }

        scenario
            .stimulate(Runnable { writeOffAdjustment.handle(notOwed, "Vendor will not bill") })
            .andWaitForEventOfType(DirectDeliveryAdjustmentResolved::class.java)
            .matching { it.adjustmentId == notOwed.value }
            .toArriveAndVerify { event ->
                event.resolution shouldBe "WRITTEN_OFF"
                event.vendorReference shouldBe null
            }
        directDeliveries.adjustment(notOwed).statusCode shouldBe "WRITTEN_OFF"
        shouldThrow<AdjustmentAlreadyResolved> { payAdjustment.handle(notOwed, document, "Too late") }
        directDeliveries.adjustments(vendorId, true, PageQuery.of()).total shouldBe 0
        directDeliveries.adjustments(vendorId, false, PageQuery.of()).total shouldBe 2
        // Written off, nothing is paid: only the two invoices and the paid adjustment have vouchers.
        vouchers.list(vendorId, null, PageQuery.of()).total shouldBe 3
        actingAs("clerk", Roles.ACCOUNTING_CLERK)
        shouldThrow<InvoiceAlreadyEntered> {
            enterDirectDeliveryInvoice.handle(
                ticketInvoice(vendorId, StoreId.generate(clock), "T-87", "DM-85", ItemId.generate(clock), 1, "1.00"),
            )
        }
    }

    /**
     * A ticket of 10 chips at 0.80, invoiced and matched, then corrected to 15: the 5 extra are an adjustment
     * of 4.00. Returns the adjustment's id.
     */
    private fun adjustmentAfterInvoice(
        vendorId: VendorId,
        ticketNumber: String,
        number: String,
    ): DeliveryAdjustmentId {
        val storeId = StoreId.generate(clock)
        val chips = ItemId.generate(clock)
        val ticket = delivered(vendorId, storeId, ticketNumber, TicketAmountLine.of(chips, 10, usd("0.80")))
        enterDirectDeliveryInvoice.handle(ticketInvoice(vendorId, storeId, ticketNumber, number, chips, 10, "0.80"))
        recordDeliveryCorrection.handle(
            RecordDeliveryCorrectionCommand(
                ticket.copy(receiptNumber = "100094"),
                delivered = listOf(TicketUnitChange(chips, 5, usd("0.80"))),
                returned = emptyList(),
            ),
        )
        return directDeliveries
            .adjustments(vendorId, true, PageQuery.of())
            .items
            .single { it.ticket.ticketNumber == ticketNumber }
            .id
    }

    private fun delivered(
        vendorId: VendorId,
        storeId: StoreId,
        ticket: String,
        line: TicketAmountLine,
    ): DeliveredTicket {
        val checkedIn = DeliveredTicket(DirectDeliveryId.generate(clock), "100090", vendorId, storeId, ticket, today)
        recordDirectDelivery.handle(RecordDirectDeliveryCommand(checkedIn, listOf(line), emptyList()))
        return checkedIn
    }

    private fun ticketInvoice(
        vendorId: VendorId,
        storeId: StoreId,
        ticket: String,
        number: String,
        itemId: ItemId,
        units: Int,
        unitCost: String,
    ) = TicketInvoiceDetails(
        BilledTicket(vendorId, storeId, ticket),
        VendorInvoiceNumber.of(number),
        today,
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
