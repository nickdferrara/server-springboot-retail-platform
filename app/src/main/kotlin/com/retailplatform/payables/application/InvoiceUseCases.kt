package com.retailplatform.payables.application

import com.retailplatform.payables.domain.InvoiceAlreadyEntered
import com.retailplatform.payables.domain.InvoiceDetails
import com.retailplatform.payables.domain.MatchOutcome
import com.retailplatform.payables.domain.MatchTolerance
import com.retailplatform.payables.domain.ThreeWayMatch
import com.retailplatform.payables.domain.VendorInvoice
import com.retailplatform.payables.domain.VendorInvoiceNumber
import com.retailplatform.platform.security.CurrentUser
import com.retailplatform.platform.security.RequiresRole
import com.retailplatform.platform.security.Roles
import com.retailplatform.shared.InvoiceId
import com.retailplatform.shared.Page
import com.retailplatform.shared.PageQuery
import com.retailplatform.shared.Percentage
import com.retailplatform.shared.ResourceNotFound
import com.retailplatform.shared.VendorId
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/** Port: where vendor invoices are kept. */
interface VendorInvoiceRepository {
    /** Inserts a new invoice or updates an existing one; a stale version is refused. */
    fun save(invoice: VendorInvoice)

    fun findById(id: InvoiceId): VendorInvoice?

    fun exists(
        vendorId: VendorId,
        number: VendorInvoiceNumber,
    ): Boolean

    /** Invoices with the newest first; only those waiting on an exception if [exceptionsOnly]. */
    fun findPage(
        exceptionsOnly: Boolean,
        query: PageQuery,
    ): Page<VendorInvoice>
}

/**
 * Runs the three-way match for an invoice against the open accruals of its purchase order, and saves what the
 * match changed: the invoice's status and, when it matched, the accruals it relieved.
 */
@Component
class InvoiceMatching(
    private val invoices: VendorInvoiceRepository,
    private val accruals: AccrualRepository,
    private val properties: PayablesProperties,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
) {
    fun match(invoice: VendorInvoice) {
        val tolerance =
            MatchTolerance.of(
                Percentage.of(properties.match.priceTolerancePercent),
                properties.match.priceToleranceAmount,
            )
        val open = accruals.findOpenByPurchaseOrder(invoice.details.purchaseOrderId)
        val outcome = ThreeWayMatch(tolerance).match(invoice, open)

        invoice.record(outcome, clock)
        invoices.save(invoice)
        if (outcome is MatchOutcome.Matched) open.forEach(accruals::save)
        invoice.releaseEvents().forEach(events::publishEvent)
    }
}

/** Use case: enter a vendor's invoice and match it against its purchase order and receipts. */
@Service
class EnterVendorInvoice(
    private val invoices: VendorInvoiceRepository,
    private val matching: InvoiceMatching,
    private val currentUser: CurrentUser,
    private val clock: Clock,
) {
    @RequiresRole(Roles.ACCOUNTING_CLERK)
    @Transactional
    fun handle(details: InvoiceDetails): InvoiceId {
        if (invoices.exists(details.vendorId, details.number)) throw InvoiceAlreadyEntered(details.number)

        val invoice = VendorInvoice.enter(details, currentUser.name, clock)
        matching.match(invoice)
        return invoice.id
    }
}

/** Use case: match an invoice again after what held it up was resolved, for example more goods arrived. */
@Service
class RematchVendorInvoice(
    private val invoices: VendorInvoiceRepository,
    private val matching: InvoiceMatching,
) {
    @RequiresRole(Roles.ACCOUNTING_CLERK)
    @Transactional
    fun handle(invoiceId: InvoiceId) {
        val invoice = invoices.findById(invoiceId) ?: throw ResourceNotFound("Invoice", invoiceId.toString())
        matching.match(invoice)
    }
}

/** Queries: vendor invoices by identifier and by page. */
@Service
@Transactional(readOnly = true)
class InvoiceQueries(
    private val invoices: VendorInvoiceRepository,
) {
    fun find(id: InvoiceId): VendorInvoice = invoices.findById(id) ?: throw ResourceNotFound("Invoice", id.toString())

    fun list(
        exceptionsOnly: Boolean,
        query: PageQuery,
    ): Page<VendorInvoice> = invoices.findPage(exceptionsOnly, query)
}
