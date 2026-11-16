package com.retailplatform.payables.application

import com.retailplatform.payables.domain.InvoiceAlreadyEntered
import com.retailplatform.payables.domain.LateBill
import com.retailplatform.payables.domain.LateBillAlreadySettled
import com.retailplatform.payables.domain.LateBillDetails
import com.retailplatform.payables.domain.LateBillId
import com.retailplatform.payables.domain.LateBillInAnotherCurrency
import com.retailplatform.payables.domain.LateBillSettledByRecorder
import com.retailplatform.payables.domain.MatchOverride
import com.retailplatform.payables.domain.TicketInvoiceStatus
import com.retailplatform.payables.domain.TicketNotClosed
import com.retailplatform.payables.domain.VendorInvoiceNumber
import com.retailplatform.platform.security.CurrentUser
import com.retailplatform.platform.security.RequiresRole
import com.retailplatform.platform.security.Roles
import com.retailplatform.shared.Page
import com.retailplatform.shared.PageQuery
import com.retailplatform.shared.ResourceNotFound
import com.retailplatform.shared.VendorId
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.Currency

/** Port: where late bills are kept. */
interface LateBillRepository {
    /** Inserts a new late bill or updates an existing one; a stale version is refused. */
    fun save(lateBill: LateBill)

    fun findById(id: LateBillId): LateBill?

    /** Whether a late bill with the vendor's document [number] has been recorded. */
    fun exists(
        vendorId: VendorId,
        number: VendorInvoiceNumber,
    ): Boolean

    /** A vendor's late bills, the oldest first; only those still waiting if [openOnly]. */
    fun findByVendor(
        vendorId: VendorId,
        openOnly: Boolean,
        query: PageQuery,
    ): Page<LateBill>
}

/**
 * Use case: record a late bill. A ticket is invoiced once, so a further bill from the vendor for a ticket whose
 * invoice is already payable cannot be entered as an invoice; a clerk records it here, with its total, for
 * accounting to decide.
 */
@Service
class RecordLateBill(
    private val lateBills: LateBillRepository,
    private val invoices: DirectDeliveryInvoiceRepository,
    private val documentNumbers: VendorDocumentNumbers,
    private val currentUser: CurrentUser,
    private val clock: Clock,
) {
    /**
     * @throws TicketNotClosed if the ticket has no invoice, or its invoice still waits in exception.
     * @throws LateBillInAnotherCurrency if the bill is not in the currency of the ticket's invoice.
     * @throws InvoiceAlreadyEntered if the vendor's document number was already entered.
     */
    @RequiresRole(Roles.ACCOUNTING_CLERK)
    @Transactional
    fun handle(details: LateBillDetails): LateBillId {
        val invoice = invoices.findFor(details.ticket)
        if (invoice == null || invoice.status is TicketInvoiceStatus.Exception) {
            throw TicketNotClosed(details.ticket.ticketNumber)
        }
        requireNew(details, invoice.amount.currency)

        val lateBill = LateBill.record(details, currentUser.name, clock)
        lateBills.save(lateBill)
        return lateBill.id
    }

    private fun requireNew(
        details: LateBillDetails,
        invoiced: Currency,
    ) {
        if (details.amount.currency != invoiced) throw LateBillInAnotherCurrency(details.number)
        if (documentNumbers.isUsed(details.ticket.vendorId, details.number)) throw InvoiceAlreadyEntered(details.number)
    }
}

/**
 * Use case: accept a late bill for payment. An accounting manager other than the person who recorded it decides
 * the vendor is owed it, and says why. A voucher is released for the bill's total on the vendor's payment terms;
 * nothing was accrued for it, so the ledger takes the whole amount as a cost variance.
 */
@Service
class PayLateBill(
    private val lateBills: LateBillRepository,
    private val voucherRelease: VoucherRelease,
    private val currentUser: CurrentUser,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
) {
    /**
     * @throws LateBillAlreadySettled if the bill was paid or rejected before.
     * @throws LateBillSettledByRecorder if the person asking recorded the bill.
     */
    @RequiresRole(Roles.ACCOUNTING_MANAGER)
    @Transactional
    fun handle(
        id: LateBillId,
        reason: String,
    ) {
        val lateBill = lateBills.findById(id) ?: throw ResourceNotFound("Late bill", id.toString())
        val paid = lateBill.pay(MatchOverride.of(currentUser.name, reason), clock)

        lateBills.save(paid)
        voucherRelease.releaseFor(paid.payable())
        events.publishEvent(paid.accepted(clock))
    }
}

/**
 * Use case: reject a late bill. An accounting manager other than the person who recorded it decides nothing is
 * owed on it, and says why. It stays on record; nothing is paid and nothing is posted.
 */
@Service
class RejectLateBill(
    private val lateBills: LateBillRepository,
    private val currentUser: CurrentUser,
    private val clock: Clock,
) {
    /**
     * @throws LateBillAlreadySettled if the bill was paid or rejected before.
     * @throws LateBillSettledByRecorder if the person asking recorded the bill.
     */
    @RequiresRole(Roles.ACCOUNTING_MANAGER)
    @Transactional
    fun handle(
        id: LateBillId,
        reason: String,
    ) {
        val lateBill = lateBills.findById(id) ?: throw ResourceNotFound("Late bill", id.toString())
        lateBills.save(lateBill.reject(MatchOverride.of(currentUser.name, reason), clock))
    }
}

/** Queries: late bills by identifier and by vendor. */
@Service
@Transactional(readOnly = true)
class LateBillQueries(
    private val lateBills: LateBillRepository,
) {
    fun find(id: LateBillId): LateBill = lateBills.findById(id) ?: throw ResourceNotFound("Late bill", id.toString())

    fun list(
        vendorId: VendorId,
        openOnly: Boolean,
        query: PageQuery,
    ): Page<LateBill> = lateBills.findByVendor(vendorId, openOnly, query)
}
