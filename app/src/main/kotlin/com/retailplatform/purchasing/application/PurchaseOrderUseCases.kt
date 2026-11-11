package com.retailplatform.purchasing.application

import com.retailplatform.locations.api.WarehouseDirectory
import com.retailplatform.platform.security.CurrentUser
import com.retailplatform.platform.security.RequiresRole
import com.retailplatform.platform.security.Roles
import com.retailplatform.purchasing.domain.Approver
import com.retailplatform.purchasing.domain.CancellationReason
import com.retailplatform.purchasing.domain.ItemNotSuppliedByVendor
import com.retailplatform.purchasing.domain.PoApprovalPolicy
import com.retailplatform.purchasing.domain.PurchaseOrder
import com.retailplatform.purchasing.domain.PurchaseOrderLine
import com.retailplatform.purchasing.domain.PurchaseOrderTerms
import com.retailplatform.purchasing.domain.RequestedLine
import com.retailplatform.purchasing.domain.UnknownVendor
import com.retailplatform.purchasing.domain.UnknownWarehouse
import com.retailplatform.shared.BusinessDate
import com.retailplatform.shared.Page
import com.retailplatform.shared.PageQuery
import com.retailplatform.shared.PurchaseOrderId
import com.retailplatform.shared.ResourceNotFound
import com.retailplatform.shared.VendorId
import com.retailplatform.shared.WarehouseId
import com.retailplatform.vendors.api.VendorDirectory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/** What a buyer enters for the delivery and lines of an order, before it is checked against master data. */
data class PurchaseOrderTermsInput(
    val warehouseId: WarehouseId,
    val expectedOn: BusinessDate,
    val lines: List<RequestedLine>,
)

/** Turns what a buyer entered into order terms, costing each line from what the vendor supplies. */
@Component
class PurchaseOrderTermsFactory(
    private val warehouses: WarehouseDirectory,
    private val costs: VendorItemCostRepository,
) {
    /**
     * @throws UnknownWarehouse if the warehouse does not exist.
     * @throws ItemNotSuppliedByVendor if the catalog has no cost from [vendorId] for an item.
     */
    fun terms(
        vendorId: VendorId,
        input: PurchaseOrderTermsInput,
    ): PurchaseOrderTerms {
        if (!warehouses.exists(input.warehouseId)) throw UnknownWarehouse(input.warehouseId)
        val supplied = costs.findByVendorAndItems(vendorId, input.lines.map { it.itemId })
        val lines =
            input.lines.map { requested ->
                val supply = supplied[requested.itemId] ?: throw ItemNotSuppliedByVendor(requested.itemId, vendorId)
                PurchaseOrderLine.of(requested, supply)
            }
        return PurchaseOrderTerms(input.warehouseId, input.expectedOn, lines)
    }
}

data class CreatePurchaseOrderCommand(
    val vendorId: VendorId,
    val terms: PurchaseOrderTermsInput,
)

/** Use case: start a draft purchase order to a vendor. */
@Service
class CreatePurchaseOrder(
    private val orders: PurchaseOrderRepository,
    private val numbers: PurchaseOrderNumbers,
    private val vendors: VendorDirectory,
    private val termsFactory: PurchaseOrderTermsFactory,
    private val currentUser: CurrentUser,
    private val clock: Clock,
) {
    @RequiresRole(Roles.BUYER)
    @Transactional
    fun handle(command: CreatePurchaseOrderCommand): PurchaseOrderId {
        if (!vendors.exists(command.vendorId)) throw UnknownVendor(command.vendorId)
        val terms = termsFactory.terms(command.vendorId, command.terms)

        val order = PurchaseOrder.draft(numbers.next(), command.vendorId, currentUser.name, terms, clock)
        orders.save(order)
        return order.id
    }
}

data class RevisePurchaseOrderCommand(
    val purchaseOrderId: PurchaseOrderId,
    val terms: PurchaseOrderTermsInput,
)

/** Use case: replace the delivery details and lines of a draft purchase order. */
@Service
class RevisePurchaseOrder(
    private val orders: PurchaseOrderRepository,
    private val termsFactory: PurchaseOrderTermsFactory,
    private val clock: Clock,
) {
    @RequiresRole(Roles.BUYER)
    @Transactional
    fun handle(command: RevisePurchaseOrderCommand) {
        val order = orders.require(command.purchaseOrderId)
        order.revise(termsFactory.terms(order.vendorId, command.terms), clock)
        orders.save(order)
    }
}

/**
 * Use case: approve a draft purchase order. Any buyer may approve an order at or under the threshold in effect;
 * above it the approver role is needed, and the buyer who created the order cannot approve it.
 */
@Service
class ApprovePurchaseOrder(
    private val orders: PurchaseOrderRepository,
    private val thresholds: ApprovalThresholdRepository,
    private val currentUser: CurrentUser,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
) {
    @RequiresRole(Roles.BUYER, Roles.BUYER_APPROVER)
    @Transactional
    fun handle(purchaseOrderId: PurchaseOrderId) {
        val order = orders.require(purchaseOrderId)
        val policy = PoApprovalPolicy(thresholds.findEffective(BusinessDate.today(clock))?.amount)
        val approver = Approver(currentUser.name, currentUser.hasRole(Roles.BUYER_APPROVER))

        order.approve(approver, policy, clock)
        orders.save(order)
        order.releaseEvents().forEach(events::publishEvent)
    }
}

/** Use case: record that an approved purchase order went to the vendor. */
@Service
class SendPurchaseOrder(
    private val orders: PurchaseOrderRepository,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
) {
    @RequiresRole(Roles.BUYER)
    @Transactional
    fun handle(purchaseOrderId: PurchaseOrderId) {
        val order = orders.require(purchaseOrderId)
        order.send(clock)
        orders.save(order)
        order.releaseEvents().forEach(events::publishEvent)
    }
}

data class CancelPurchaseOrderCommand(
    val purchaseOrderId: PurchaseOrderId,
    val reason: CancellationReason,
)

/** Use case: cancel a purchase order that has not been cancelled already. */
@Service
class CancelPurchaseOrder(
    private val orders: PurchaseOrderRepository,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
) {
    @RequiresRole(Roles.BUYER)
    @Transactional
    fun handle(command: CancelPurchaseOrderCommand) {
        val order = orders.require(command.purchaseOrderId)
        order.cancel(command.reason, clock)
        orders.save(order)
        order.releaseEvents().forEach(events::publishEvent)
    }
}

/** Queries: purchase orders by identifier and by page. */
@Service
@Transactional(readOnly = true)
class PurchaseOrderQueries(
    private val orders: PurchaseOrderRepository,
) {
    fun find(id: PurchaseOrderId): PurchaseOrder = orders.require(id)

    fun list(query: PageQuery): Page<PurchaseOrder> = orders.findPage(query)
}

private fun PurchaseOrderRepository.require(id: PurchaseOrderId): PurchaseOrder =
    findById(id) ?: throw ResourceNotFound("Purchase order", id.toString())
