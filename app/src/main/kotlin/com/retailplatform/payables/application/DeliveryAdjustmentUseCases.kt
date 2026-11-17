package com.retailplatform.payables.application

import com.retailplatform.payables.domain.AdjustmentAlreadyResolved
import com.retailplatform.payables.domain.AdjustmentResolution
import com.retailplatform.payables.domain.DeliveryAdjustment
import com.retailplatform.payables.domain.DeliveryAdjustmentId
import com.retailplatform.payables.domain.InvoiceAlreadyEntered
import com.retailplatform.payables.domain.MatchOverride
import com.retailplatform.payables.domain.VendorInvoiceNumber
import com.retailplatform.platform.security.CurrentUser
import com.retailplatform.platform.security.RequiresRole
import com.retailplatform.platform.security.Roles
import com.retailplatform.shared.BusinessDate
import com.retailplatform.shared.ResourceNotFound
import com.retailplatform.shared.VendorId
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/** The vendor's document an adjustment is paid against: its number and its date. */
data class VendorDocument(
    val reference: VendorInvoiceNumber,
    val date: BusinessDate,
)

/**
 * Knows every document number a vendor has been paid against, so one is not paid twice: its purchase order
 * invoices, its direct delivery invoices, the documents its adjustments were paid on, its late bills, and the
 * claims it disputed deductions with.
 */
@Component
class VendorDocumentNumbers(
    private val vendorInvoices: VendorInvoiceRepository,
    private val ticketInvoices: DirectDeliveryInvoiceRepository,
    private val adjustments: DeliveryAdjustmentRepository,
    private val lateBills: LateBillRepository,
    private val disputes: DeductionDisputeRepository,
) {
    fun isUsed(
        vendorId: VendorId,
        number: VendorInvoiceNumber,
    ): Boolean =
        vendorInvoices.exists(vendorId, number) ||
            ticketInvoices.exists(vendorId, number) ||
            adjustments.existsReference(vendorId, number) ||
            lateBills.exists(vendorId, number) ||
            disputes.existsReference(vendorId, number)
}

/**
 * Use case: pay a direct delivery adjustment. The vendor is owed the amount and has billed it on a document of
 * its own; an accounting manager says which and why, and a voucher is released for the whole amount on the
 * vendor's payment terms.
 */
@Service
class PayDeliveryAdjustment(
    private val adjustments: DeliveryAdjustmentRepository,
    private val documentNumbers: VendorDocumentNumbers,
    private val voucherRelease: VoucherRelease,
    private val currentUser: CurrentUser,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
) {
    /**
     * @throws AdjustmentAlreadyResolved if the adjustment was paid or written off before.
     * @throws InvoiceAlreadyEntered if the vendor's document number was already entered or paid against.
     */
    @RequiresRole(Roles.ACCOUNTING_MANAGER)
    @Transactional
    fun handle(
        id: DeliveryAdjustmentId,
        document: VendorDocument,
        reason: String,
    ) {
        val adjustment = adjustments.findById(id) ?: throw ResourceNotFound("Adjustment", id.toString())
        val paid =
            adjustment.resolve(
                AdjustmentResolution.Paid(
                    BusinessDate.today(clock),
                    MatchOverride.of(currentUser.name, reason),
                    document.reference,
                    document.date,
                ),
            )
        if (documentNumbers.isUsed(adjustment.ticket.vendorId, document.reference)) {
            throw InvoiceAlreadyEntered(document.reference)
        }

        adjustments.save(paid)
        voucherRelease.releaseFor(paid.payable())
        events.publishEvent(paid.resolved(clock))
    }
}

/**
 * Use case: write off a direct delivery adjustment. The vendor is not owed the amount, so an accounting manager
 * releases it from what is accrued as a favourable variance, and says why.
 */
@Service
class WriteOffDeliveryAdjustment(
    private val adjustments: DeliveryAdjustmentRepository,
    private val currentUser: CurrentUser,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
) {
    /** @throws AdjustmentAlreadyResolved if the adjustment was paid or written off before. */
    @RequiresRole(Roles.ACCOUNTING_MANAGER)
    @Transactional
    fun handle(
        id: DeliveryAdjustmentId,
        reason: String,
    ) {
        val adjustment = adjustments.findById(id) ?: throw ResourceNotFound("Adjustment", id.toString())
        val writtenOff: DeliveryAdjustment =
            adjustment.resolve(
                AdjustmentResolution.WrittenOff(BusinessDate.today(clock), MatchOverride.of(currentUser.name, reason)),
            )

        adjustments.save(writtenOff)
        events.publishEvent(writtenOff.resolved(clock))
    }
}
