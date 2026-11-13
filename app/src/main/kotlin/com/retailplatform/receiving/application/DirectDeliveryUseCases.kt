package com.retailplatform.receiving.application

import com.retailplatform.locations.api.StoreDirectory
import com.retailplatform.platform.security.CurrentUser
import com.retailplatform.platform.security.RequiresRole
import com.retailplatform.platform.security.Roles
import com.retailplatform.receiving.domain.AgreedCost
import com.retailplatform.receiving.domain.AgreedCostEffectiveInPast
import com.retailplatform.receiving.domain.DeliveryTicket
import com.retailplatform.receiving.domain.DeliveryTicketAlreadyReceived
import com.retailplatform.receiving.domain.DirectDelivery
import com.retailplatform.receiving.domain.ReceiptHeader
import com.retailplatform.receiving.domain.TicketLine
import com.retailplatform.receiving.domain.UnknownDeliveryParty
import com.retailplatform.shared.BusinessDate
import com.retailplatform.shared.DirectDeliveryId
import com.retailplatform.shared.ItemId
import com.retailplatform.shared.Money
import com.retailplatform.shared.Page
import com.retailplatform.shared.PageQuery
import com.retailplatform.shared.ResourceNotFound
import com.retailplatform.shared.StoreId
import com.retailplatform.shared.VendorId
import com.retailplatform.vendors.api.VendorDirectory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/** Port: what each vendor charges each store for the items it delivers there directly. */
interface AgreedCostRepository {
    /** Records [cost], replacing a cost already set for the same vendor, item, store, and effective date. */
    fun save(
        cost: AgreedCost,
        setBy: String,
    )

    /** The cost in effect on [asOf] for each item the vendor may deliver to the store. */
    fun findEffective(
        vendorId: VendorId,
        storeId: StoreId,
        asOf: BusinessDate,
    ): List<AgreedCost>
}

/** Port: where direct store deliveries are kept. They are added and read, never changed. */
interface DirectDeliveryRepository {
    fun add(delivery: DirectDelivery)

    fun findById(id: DirectDeliveryId): DirectDelivery?

    fun exists(ticket: DeliveryTicket): Boolean

    /** A store's deliveries with the newest first. */
    fun findByStore(
        storeId: StoreId,
        query: PageQuery,
    ): Page<DirectDelivery>
}

data class SetAgreedCostCommand(
    val vendorId: VendorId,
    val itemId: ItemId,
    val storeIds: List<StoreId>,
    val unitCost: Money,
    val effectiveFrom: BusinessDate,
)

/**
 * Use case: set what a vendor charges for one base unit of an item at each of the given stores, from a date on.
 * Setting a cost is also what allows the vendor to deliver the item to those stores.
 */
@Service
class SetAgreedCost(
    private val costs: AgreedCostRepository,
    private val vendors: VendorDirectory,
    private val stores: StoreDirectory,
    private val currentUser: CurrentUser,
    private val clock: Clock,
) {
    /**
     * @throws AgreedCostEffectiveInPast if the cost would take effect before today; deliveries already checked in
     * keep the cost they were checked in at.
     * @throws UnknownDeliveryParty if the vendor or a store does not exist.
     */
    @RequiresRole(Roles.BUYER)
    @Transactional
    fun handle(command: SetAgreedCostCommand) {
        val today = BusinessDate.today(clock)
        if (command.effectiveFrom < today) throw AgreedCostEffectiveInPast(command.effectiveFrom, today)
        requireKnown(command)

        command.storeIds.distinct().forEach { storeId ->
            costs.save(
                AgreedCost.of(command.vendorId, command.itemId, storeId, command.unitCost, command.effectiveFrom),
                currentUser.name,
            )
        }
    }

    private fun requireKnown(command: SetAgreedCostCommand) {
        if (!vendors.exists(command.vendorId)) throw UnknownDeliveryParty("Vendor", command.vendorId)
        command.storeIds.firstOrNull { !stores.exists(it) }?.let { throw UnknownDeliveryParty("Store", it) }
    }
}

data class ReceiveDirectDeliveryCommand(
    val ticket: DeliveryTicket,
    val counted: List<TicketLine>,
)

/** Use case: check in a vendor's delivery at a store from the driver's ticket, including what was taken back. */
@Service
class ReceiveDirectDelivery(
    private val deliveries: DirectDeliveryRepository,
    private val costs: AgreedCostRepository,
    private val numbers: ReceiptNumbers,
    private val currentUser: CurrentUser,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
) {
    /** @throws DeliveryTicketAlreadyReceived if the vendor's ticket was already checked in at the store. */
    @RequiresRole(Roles.STORE_MANAGER)
    @Transactional
    fun handle(command: ReceiveDirectDeliveryCommand): DirectDeliveryId {
        val ticket = command.ticket
        if (deliveries.exists(ticket)) throw DeliveryTicketAlreadyReceived(ticket.number)

        val agreed =
            costs
                .findEffective(ticket.vendorId, ticket.storeId, BusinessDate.today(clock))
                .associate { it.itemId to it.unitCost }
        val header = ReceiptHeader(numbers.next(), currentUser.name)
        val delivery = DirectDelivery.receive(header, ticket, command.counted, agreed, clock)
        deliveries.add(delivery)
        delivery.releaseEvents().forEach(events::publishEvent)
        return delivery.id
    }
}

/** Queries: direct store deliveries and the costs agreed for them. */
@Service
@Transactional(readOnly = true)
class DirectDeliveryQueries(
    private val deliveries: DirectDeliveryRepository,
    private val costs: AgreedCostRepository,
    private val clock: Clock,
) {
    fun delivery(id: DirectDeliveryId): DirectDelivery =
        deliveries.findById(id) ?: throw ResourceNotFound("Direct delivery", id.toString())

    fun deliveriesTo(
        storeId: StoreId,
        query: PageQuery,
    ): Page<DirectDelivery> = deliveries.findByStore(storeId, query)

    /** What the vendor charges the store for each item on [asOf], today unless given. */
    fun agreedCosts(
        vendorId: VendorId,
        storeId: StoreId,
        asOf: BusinessDate?,
    ): List<AgreedCost> = costs.findEffective(vendorId, storeId, asOf ?: BusinessDate.today(clock))
}
