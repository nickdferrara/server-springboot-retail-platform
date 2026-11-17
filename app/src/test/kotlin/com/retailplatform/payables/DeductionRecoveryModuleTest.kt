package com.retailplatform.payables

import com.retailplatform.TestcontainersConfiguration
import com.retailplatform.payables.application.ConcedeDeduction
import com.retailplatform.payables.application.DeductionDisputeQueries
import com.retailplatform.payables.application.DeductionNoticeQueries
import com.retailplatform.payables.application.DirectDeliveryPayablesQueries
import com.retailplatform.payables.application.EnterDirectDeliveryInvoice
import com.retailplatform.payables.application.PayDeliveryAdjustment
import com.retailplatform.payables.application.RecordDeductionDispute
import com.retailplatform.payables.application.RecordDeliveryCorrection
import com.retailplatform.payables.application.RecordDeliveryCorrectionCommand
import com.retailplatform.payables.application.RecordDirectDelivery
import com.retailplatform.payables.application.RecordDirectDeliveryCommand
import com.retailplatform.payables.application.ShortPayDirectDeliveryInvoice
import com.retailplatform.payables.application.VendorDocument
import com.retailplatform.payables.application.VoucherQueries
import com.retailplatform.payables.application.WriteOffDeliveryAdjustment
import com.retailplatform.payables.domain.AdjustmentCoveredByConcession
import com.retailplatform.payables.domain.AdjustmentPartInvalid
import com.retailplatform.payables.domain.BilledTicket
import com.retailplatform.payables.domain.DeductionAlreadyRecovered
import com.retailplatform.payables.domain.DeductionNoticeId
import com.retailplatform.payables.domain.DeliveredTicket
import com.retailplatform.payables.domain.DeliveryAdjustmentId
import com.retailplatform.payables.domain.DisputeClaim
import com.retailplatform.payables.domain.DisputeStatus
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
import com.retailplatform.shared.StoreId
import com.retailplatform.shared.VendorId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.modulith.test.ApplicationModuleTest
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.test.context.support.WithMockUser
import java.time.Clock

/** Units a short-pay held back are paid for once: through an adjustment or a conceded dispute, not both. */
@ApplicationModuleTest
@Import(TestcontainersConfiguration::class)
@WithMockUser(username = "clerk", authorities = [Roles.ACCOUNTING_CLERK])
class DeductionRecoveryModuleTest(
    @Autowired private val recordDirectDelivery: RecordDirectDelivery,
    @Autowired private val recordDeliveryCorrection: RecordDeliveryCorrection,
    @Autowired private val enterDirectDeliveryInvoice: EnterDirectDeliveryInvoice,
    @Autowired private val shortPayInvoice: ShortPayDirectDeliveryInvoice,
    @Autowired private val notices: DeductionNoticeQueries,
    @Autowired private val recordDispute: RecordDeductionDispute,
    @Autowired private val concedeDeduction: ConcedeDeduction,
    @Autowired private val disputes: DeductionDisputeQueries,
    @Autowired private val directDeliveries: DirectDeliveryPayablesQueries,
    @Autowired private val payAdjustment: PayDeliveryAdjustment,
    @Autowired private val writeOffAdjustment: WriteOffDeliveryAdjustment,
    @Autowired private val vouchers: VoucherQueries,
    @Autowired private val clock: Clock,
) {
    private val today get() = BusinessDate.today(clock)
    private val cola = ItemId.generate(clock)
    private val chips = ItemId.generate(clock)

    @Test
    fun `units the store found after the short-pay cannot be conceded until their adjustment is written off`() {
        val vendorId = VendorId.generate(clock)
        val shortPaid = shortPaid(vendorId, "T-140", "DSD-140")
        // The store finds one of the two colas held back.
        val adjustment = corrected(shortPaid, "100141", TicketUnitChange(cola, 1, usd("1.25")))
        directDeliveries.adjustment(adjustment).lines shouldBe listOf(TicketAmountLine.of(cola, 1, usd("1.25")))
        val dispute = recordDispute.handle(shortPaid.noticeId, claim("CLAIM-140"))
        disputes.concedable(disputes.find(dispute)) shouldBe usd("1.25")

        actingAs("controller", Roles.ACCOUNTING_MANAGER)
        shouldThrow<DeductionAlreadyRecovered> { concedeDeduction.handle(dispute, usd("2.50"), "Both were delivered") }

        // Written off, the adjustment covers nothing, and the whole deduction can be conceded.
        writeOffAdjustment.handle(adjustment, "Settling through the dispute instead")
        disputes.concedable(disputes.find(dispute)) shouldBe usd("2.50")
        concedeDeduction.handle(dispute, usd("2.50"), "Both were delivered")

        disputes.find(dispute).status shouldBe
            DisputeStatus.Conceded(today, MatchOverride.of("controller", "Both were delivered"), usd("2.50"))
        disputes.concedable(disputes.find(dispute)) shouldBe null
    }

    @Test
    fun `an adjustment for units a concession already gave back is written off, not paid`() {
        val vendorId = VendorId.generate(clock)
        val shortPaid = shortPaid(vendorId, "T-142", "DSD-142")
        val dispute = recordDispute.handle(shortPaid.noticeId, claim("CLAIM-142"))
        actingAs("controller", Roles.ACCOUNTING_MANAGER)
        concedeDeduction.handle(dispute, usd("2.50"), "Both were delivered")

        // The store then corrects its receipt: the two colas, and five chips the invoice never billed.
        val colas = corrected(shortPaid, "100143", TicketUnitChange(cola, 2, usd("1.25")))
        val extraChips = corrected(shortPaid, "100144", TicketUnitChange(chips, 5, usd("0.80")))
        val document = VendorDocument(VendorInvoiceNumber.of("DM-142"), today)

        shouldThrow<AdjustmentCoveredByConcession> { payAdjustment.handle(colas, document, "Vendor billed them") }
        // The chips were never held back, so they are still owed and paid.
        payAdjustment.handle(extraChips, document, "Vendor billed the chips")
        writeOffAdjustment.handle(colas, "Already given back through the dispute")

        directDeliveries.adjustment(extraChips).statusCode shouldBe "PAID"
        directDeliveries.adjustment(colas).statusCode shouldBe "WRITTEN_OFF"
        directDeliveries.adjustments(vendorId, true, PageQuery.of()).total shouldBe 0
    }

    @Test
    fun `when a concession gave back some of the units, the rest of their adjustment is paid in part`() {
        val vendorId = VendorId.generate(clock)
        val shortPaid = shortPaid(vendorId, "T-145", "DSD-145")
        val dispute = recordDispute.handle(shortPaid.noticeId, claim("CLAIM-145"))
        actingAs("controller", Roles.ACCOUNTING_MANAGER)
        // One of the two colas held back is conceded; the store then finds both.
        concedeDeduction.handle(dispute, usd("1.25"), "One was delivered")
        val colas = corrected(shortPaid, "100146", TicketUnitChange(cola, 2, usd("1.25")))
        val document = VendorDocument(VendorInvoiceNumber.of("DM-145"), today)

        shouldThrow<AdjustmentCoveredByConcession> { payAdjustment.handle(colas, document, "Vendor billed both") }
        shouldThrow<AdjustmentPartInvalid> { payAdjustment.handle(colas, document, "Three", listOf(cola to 3)) }
        payAdjustment.handle(colas, document, "The other cola", listOf(cola to 1))

        val settled = directDeliveries.adjustment(colas)
        settled.statusCode shouldBe "PAID"
        settled.paidAmount shouldBe usd("1.25")
        settled.writtenOffAmount shouldBe usd("1.25")
        settled.coveredLines shouldBe listOf(TicketAmountLine.of(cola, 1, usd("1.25")))
        vouchers
            .list(vendorId, null, PageQuery.of())
            .items
            .count { it.amount == usd("1.25") } shouldBe 2
    }

    private data class ShortPaid(
        val ticket: DeliveredTicket,
        val noticeId: DeductionNoticeId,
    )

    /**
     * A ticket of 18 colas at 1.25, billed for 20 and paid short by "manager": 2 colas, 2.50, are held back.
     * Leaves the clerk signed in.
     */
    private fun shortPaid(
        vendorId: VendorId,
        ticketNumber: String,
        number: String,
    ): ShortPaid {
        val storeId = StoreId.generate(clock)
        val checkedIn =
            DeliveredTicket(DirectDeliveryId.generate(clock), "100140", vendorId, storeId, ticketNumber, today)
        recordDirectDelivery.handle(
            RecordDirectDeliveryCommand(checkedIn, listOf(TicketAmountLine.of(cola, 18, usd("1.25"))), emptyList()),
        )
        val invoiceId =
            enterDirectDeliveryInvoice.handle(
                TicketInvoiceDetails(
                    BilledTicket(vendorId, storeId, ticketNumber),
                    VendorInvoiceNumber.of(number),
                    today,
                    listOf(TicketAmountLine.of(cola, 20, usd("1.25"))),
                ),
            )
        actingAs("manager", Roles.ACCOUNTING_MANAGER)
        shortPayInvoice.handle(invoiceId, "Store count confirmed with the driver")
        actingAs("clerk", Roles.ACCOUNTING_CLERK)
        return ShortPaid(checkedIn, notices.forInvoice(invoiceId).id)
    }

    /** The store corrects its receipt by [change] after the invoice was paid; returns the adjustment it leaves. */
    private fun corrected(
        shortPaid: ShortPaid,
        receiptNumber: String,
        change: TicketUnitChange,
    ): DeliveryAdjustmentId {
        recordDeliveryCorrection.handle(
            RecordDeliveryCorrectionCommand(
                shortPaid.ticket.copy(receiptNumber = receiptNumber),
                delivered = listOf(change),
                returned = emptyList(),
            ),
        )
        return directDeliveries
            .adjustments(shortPaid.ticket.vendorId, true, PageQuery.of())
            .items
            .single { it.ticket.receiptNumber == receiptNumber }
            .id
    }

    private fun claim(reference: String): DisputeClaim =
        DisputeClaim.of(today, VendorInvoiceNumber.of(reference), "We delivered all 20 colas billed")

    private fun actingAs(
        user: String,
        vararg roles: String,
    ) {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(user, "n/a", roles.map(::SimpleGrantedAuthority))
    }

    private fun usd(amount: String) = Money.of(amount, "USD")
}
