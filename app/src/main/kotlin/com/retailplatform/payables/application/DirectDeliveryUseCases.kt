package com.retailplatform.payables.application

import com.retailplatform.payables.domain.DeliveredTicket
import com.retailplatform.payables.domain.DirectDeliveryAccrual
import com.retailplatform.payables.domain.TicketAmountLine
import com.retailplatform.payables.domain.VendorCredit
import com.retailplatform.shared.DirectDeliveryId
import com.retailplatform.shared.Page
import com.retailplatform.shared.PageQuery
import com.retailplatform.shared.VendorId
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/** Port: where accruals for direct store deliveries are kept, one per delivery. */
interface DirectDeliveryAccrualRepository {
    fun save(accrual: DirectDeliveryAccrual)

    fun existsFor(deliveryId: DirectDeliveryId): Boolean

    /** A vendor's accruals, the oldest delivery first. */
    fun findByVendor(
        vendorId: VendorId,
        query: PageQuery,
    ): Page<DirectDeliveryAccrual>
}

/** Port: where vendor credits are kept, at most one per delivery. */
interface VendorCreditRepository {
    fun save(credit: VendorCredit)

    fun existsFor(deliveryId: DirectDeliveryId): Boolean

    /** A vendor's credits, the oldest first. */
    fun findByVendor(
        vendorId: VendorId,
        query: PageQuery,
    ): Page<VendorCredit>
}

data class RecordDirectDeliveryCommand(
    val ticket: DeliveredTicket,
    val delivered: List<TicketAmountLine>,
    val returned: List<TicketAmountLine>,
)

/**
 * Use case: record what a direct store delivery means for payables. The units delivered are accrued as owed to
 * the vendor; the units the driver took back become a credit the vendor owes.
 */
@Service
class RecordDirectDelivery(
    private val accruals: DirectDeliveryAccrualRepository,
    private val credits: VendorCreditRepository,
    private val clock: Clock,
) {
    /** Does nothing for a delivery already recorded, so a delivery is never accrued or credited twice. */
    @Transactional
    fun handle(command: RecordDirectDeliveryCommand) {
        val deliveryId = command.ticket.deliveryId
        if (command.delivered.isNotEmpty() && !accruals.existsFor(deliveryId)) {
            accruals.save(DirectDeliveryAccrual.record(command.ticket, command.delivered))
        }
        if (command.returned.isNotEmpty() && !credits.existsFor(deliveryId)) {
            credits.save(VendorCredit.record(command.ticket, command.returned, clock))
        }
    }
}

/** Queries: what is accrued for a vendor's direct deliveries, and the credits the vendor owes. */
@Service
@Transactional(readOnly = true)
class DirectDeliveryPayablesQueries(
    private val accruals: DirectDeliveryAccrualRepository,
    private val credits: VendorCreditRepository,
) {
    fun accruals(
        vendorId: VendorId,
        query: PageQuery,
    ): Page<DirectDeliveryAccrual> = accruals.findByVendor(vendorId, query)

    fun credits(
        vendorId: VendorId,
        query: PageQuery,
    ): Page<VendorCredit> = credits.findByVendor(vendorId, query)
}
