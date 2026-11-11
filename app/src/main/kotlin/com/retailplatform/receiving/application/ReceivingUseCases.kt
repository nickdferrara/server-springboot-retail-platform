package com.retailplatform.receiving.application

import com.retailplatform.platform.security.CurrentUser
import com.retailplatform.platform.security.RequiresRole
import com.retailplatform.platform.security.Roles
import com.retailplatform.receiving.domain.CountedLine
import com.retailplatform.receiving.domain.ExpectedDelivery
import com.retailplatform.receiving.domain.PurchaseOrderNotExpected
import com.retailplatform.receiving.domain.Receipt
import com.retailplatform.receiving.domain.ReceiptHeader
import com.retailplatform.shared.Page
import com.retailplatform.shared.PageQuery
import com.retailplatform.shared.PurchaseOrderId
import com.retailplatform.shared.ReceiptId
import com.retailplatform.shared.ResourceNotFound
import com.retailplatform.shared.WarehouseId
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/** Use case: start expecting a purchase order that was sent to its vendor. */
@Service
class ExpectDelivery(
    private val deliveries: ExpectedDeliveryRepository,
) {
    @Transactional
    fun handle(delivery: ExpectedDelivery) {
        if (deliveries.findById(delivery.against.purchaseOrderId) == null) deliveries.save(delivery)
    }
}

/** Use case: stop expecting a purchase order its buyer cancelled. */
@Service
class CancelExpectedDelivery(
    private val deliveries: ExpectedDeliveryRepository,
) {
    @Transactional
    fun handle(purchaseOrderId: PurchaseOrderId) {
        val delivery = deliveries.findById(purchaseOrderId) ?: return
        delivery.cancel()
        deliveries.save(delivery)
    }
}

data class ReceiveGoodsCommand(
    val purchaseOrderId: PurchaseOrderId,
    val counted: List<CountedLine>,
    val finalDelivery: Boolean,
)

/** Use case: check a vendor's delivery in at the dock against its purchase order. */
@Service
class ReceiveGoods(
    private val deliveries: ExpectedDeliveryRepository,
    private val receipts: ReceiptRepository,
    private val numbers: ReceiptNumbers,
    private val currentUser: CurrentUser,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
) {
    @RequiresRole(Roles.RECEIVING_CLERK)
    @Transactional
    fun handle(command: ReceiveGoodsCommand): ReceiptId {
        val delivery =
            deliveries.findById(command.purchaseOrderId) ?: throw PurchaseOrderNotExpected(command.purchaseOrderId)

        val header = ReceiptHeader(numbers.next(), currentUser.name)
        val receipt = delivery.receive(command.counted, command.finalDelivery, header, clock)
        deliveries.save(delivery)
        receipts.add(receipt)
        delivery.releaseEvents().forEach(events::publishEvent)
        return receipt.id
    }
}

/** Queries: what a warehouse expects, and the receipts written against purchase orders. */
@Service
@Transactional(readOnly = true)
class ReceivingQueries(
    private val deliveries: ExpectedDeliveryRepository,
    private val receipts: ReceiptRepository,
) {
    fun expected(purchaseOrderId: PurchaseOrderId): ExpectedDelivery =
        deliveries.findById(purchaseOrderId) ?: throw ResourceNotFound("Expected delivery", purchaseOrderId.toString())

    fun openDeliveries(
        warehouseId: WarehouseId,
        query: PageQuery,
    ): Page<ExpectedDelivery> = deliveries.findOpen(warehouseId, query)

    fun receipt(id: ReceiptId): Receipt = receipts.findById(id) ?: throw ResourceNotFound("Receipt", id.toString())

    fun receiptsFor(purchaseOrderId: PurchaseOrderId): List<Receipt> = receipts.findByPurchaseOrder(purchaseOrderId)
}
