package com.retailplatform.payables.application

import com.retailplatform.payables.domain.MatchedInvoice
import com.retailplatform.payables.domain.PaymentWindow
import com.retailplatform.payables.domain.VendorTerms
import com.retailplatform.payables.domain.Voucher
import com.retailplatform.payables.domain.VoucherNumber
import com.retailplatform.shared.BusinessDate
import com.retailplatform.shared.Page
import com.retailplatform.shared.PageQuery
import com.retailplatform.shared.ResourceNotFound
import com.retailplatform.shared.VendorId
import com.retailplatform.shared.VoucherId
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/** Port: where payable vouchers are kept, one per matched invoice. */
interface VoucherRepository {
    /** Inserts a new voucher or updates an existing one; a stale version is refused. */
    fun save(voucher: Voucher)

    fun findById(id: VoucherId): Voucher?

    fun findByIds(ids: List<VoucherId>): List<Voucher>

    /**
     * The released vouchers [window] selects, by vendor and then due date. Their rows are locked until the
     * transaction ends, so two payment runs proposed at once cannot both select a voucher.
     */
    fun findPayable(window: PaymentWindow): List<Voucher>

    /** Vouchers by due date, the earliest first; narrowed to [vendorId] and to a status [code] when given. */
    fun findPage(
        vendorId: VendorId?,
        statusCode: String?,
        query: PageQuery,
    ): Page<Voucher>
}

/** Port: hands out voucher numbers, each one once. */
fun interface VoucherNumbers {
    fun next(): VoucherNumber
}

/** Port: each vendor's payment terms, as the vendors module announced them. */
interface VendorTermsRepository {
    /** Records that [terms] apply to the vendor's invoices dated on or after [effectiveFrom]. */
    fun save(
        vendorId: VendorId,
        effectiveFrom: BusinessDate,
        terms: VendorTerms,
    )

    /**
     * The terms for an invoice dated [invoiceDate]: the latest that took effect on or before it, or else the
     * vendor's earliest; `null` if nothing is known about the vendor.
     */
    fun findFor(
        vendorId: VendorId,
        invoiceDate: BusinessDate,
    ): VendorTerms?
}

/** Use case: keep a vendor's payment terms, so its vouchers fall due and are discounted as agreed. */
@Service
class RecordVendorTerms(
    private val vendorTerms: VendorTermsRepository,
) {
    @Transactional
    fun handle(
        vendorId: VendorId,
        effectiveFrom: BusinessDate,
        terms: VendorTerms,
    ) = vendorTerms.save(vendorId, effectiveFrom, terms)
}

/**
 * Releases the payable voucher for an invoice that has just matched. A voucher exists only for a matched invoice,
 * so nothing is paid that did not pass the three-way match or an approved override.
 */
@Component
class VoucherRelease(
    private val vouchers: VoucherRepository,
    private val numbers: VoucherNumbers,
    private val vendorTerms: VendorTermsRepository,
    private val properties: PayablesProperties,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
) {
    fun releaseFor(matched: MatchedInvoice) {
        // A vendor whose terms have not reached payables yet is paid on the configured default terms.
        val terms =
            vendorTerms.findFor(matched.invoice.vendorId, matched.invoice.invoiceDate)
                ?: VendorTerms.of(properties.defaultNetDays)
        val voucher = Voucher.release(numbers.next(), matched, terms, clock)
        vouchers.save(voucher)
        voucher.releaseEvents().forEach(events::publishEvent)
    }
}

/** Queries: payable vouchers by identifier and by page. */
@Service
@Transactional(readOnly = true)
class VoucherQueries(
    private val vouchers: VoucherRepository,
) {
    fun find(id: VoucherId): Voucher = vouchers.findById(id) ?: throw ResourceNotFound("Voucher", id.toString())

    fun list(
        vendorId: VendorId?,
        statusCode: String?,
        query: PageQuery,
    ): Page<Voucher> = vouchers.findPage(vendorId, statusCode, query)
}
