package com.retailplatform.catalog.application

import com.retailplatform.catalog.domain.CategoryCode
import com.retailplatform.catalog.domain.Item
import com.retailplatform.catalog.domain.ItemDescription
import com.retailplatform.catalog.domain.ItemDetails
import com.retailplatform.catalog.domain.ItemNumber
import com.retailplatform.catalog.domain.ItemNumberAlreadyInUse
import com.retailplatform.catalog.domain.ItemUnits
import com.retailplatform.catalog.domain.UnknownCategory
import com.retailplatform.catalog.domain.UnknownUnitOfMeasure
import com.retailplatform.catalog.domain.UnknownVendor
import com.retailplatform.catalog.domain.Upc
import com.retailplatform.catalog.domain.UpcAlreadyAssigned
import com.retailplatform.catalog.domain.VendorItem
import com.retailplatform.platform.security.RequiresRole
import com.retailplatform.platform.security.Roles
import com.retailplatform.shared.BusinessDate
import com.retailplatform.shared.ItemId
import com.retailplatform.shared.Page
import com.retailplatform.shared.PageQuery
import com.retailplatform.shared.ResourceNotFound
import com.retailplatform.vendors.api.VendorDirectory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/** What merchandising enters for an item, before it is checked against the rest of the catalog. */
data class ItemDetailsInput(
    val description: ItemDescription,
    val categoryCode: CategoryCode,
    val units: ItemUnits,
    val upcs: Set<Upc>,
)

/**
 * Policy: an item's category must exist, its units must be defined, and its barcodes must not be on another item.
 */
@Component
class ItemDetailsPolicy(
    private val hierarchy: HierarchyRepository,
    private val units: UnitOfMeasureRepository,
    private val items: ItemRepository,
) {
    /** Checks [input] and resolves its category. [itemId] is the item being changed, if it already exists. */
    fun resolve(
        input: ItemDetailsInput,
        itemId: ItemId?,
    ): ItemDetails {
        requireUnitsDefined(input.units)
        requireUpcsFree(input.upcs, itemId)
        val category = hierarchy.findCategory(input.categoryCode) ?: throw UnknownCategory(input.categoryCode)
        return ItemDetails(input.description, category.placement(), input.units, input.upcs)
    }

    private fun requireUnitsDefined(itemUnits: ItemUnits) {
        val undefined = itemUnits.all - units.findDefined(itemUnits.all)
        if (undefined.isNotEmpty()) throw UnknownUnitOfMeasure(undefined)
    }

    private fun requireUpcsFree(
        upcs: Set<Upc>,
        itemId: ItemId?,
    ) {
        val takenByOthers = items.findOwners(upcs).filterValues { it != itemId }.keys
        if (takenByOthers.isNotEmpty()) throw UpcAlreadyAssigned(takenByOthers)
    }
}

data class CreateItemCommand(
    val number: ItemNumber,
    val details: ItemDetailsInput,
)

/** Use case: add an item to the catalog. */
@Service
class CreateItem(
    private val items: ItemRepository,
    private val policy: ItemDetailsPolicy,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
) {
    @RequiresRole(Roles.MASTER_DATA_EDITOR)
    @Transactional
    fun handle(command: CreateItemCommand): ItemId {
        if (items.existsByNumber(command.number)) throw ItemNumberAlreadyInUse(command.number)

        val item = Item.create(command.number, policy.resolve(command.details, itemId = null), clock)
        items.save(item)
        item.releaseEvents().forEach(events::publishEvent)
        return item.id
    }
}

data class ChangeItemCommand(
    val itemId: ItemId,
    val details: ItemDetailsInput,
)

/** Use case: change an item's description, category, packs, and barcodes. */
@Service
class ChangeItem(
    private val items: ItemRepository,
    private val policy: ItemDetailsPolicy,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
) {
    @RequiresRole(Roles.MASTER_DATA_EDITOR)
    @Transactional
    fun handle(command: ChangeItemCommand) {
        val item = items.findById(command.itemId) ?: throw ResourceNotFound("Item", command.itemId.toString())

        item.change(policy.resolve(command.details, item.id), clock)
        items.save(item)
        item.releaseEvents().forEach(events::publishEvent)
    }
}

data class DiscontinueItemCommand(
    val itemId: ItemId,
    val lastDay: BusinessDate,
)

/** Use case: stop buying and selling an item. */
@Service
class DiscontinueItem(
    private val items: ItemRepository,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
) {
    @RequiresRole(Roles.MASTER_DATA_EDITOR)
    @Transactional
    fun handle(command: DiscontinueItemCommand) {
        val item = items.findById(command.itemId) ?: throw ResourceNotFound("Item", command.itemId.toString())

        item.discontinue(command.lastDay, clock)
        items.save(item)
        item.releaseEvents().forEach(events::publishEvent)
    }
}

data class SetVendorItemCommand(
    val itemId: ItemId,
    val vendorItem: VendorItem,
)

/** Use case: assign a vendor to an item, or change that vendor's SKU, case pack, or list cost. */
@Service
class SetVendorItem(
    private val items: ItemRepository,
    private val vendors: VendorDirectory,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
) {
    @RequiresRole(Roles.MASTER_DATA_EDITOR)
    @Transactional
    fun handle(command: SetVendorItemCommand) {
        val item = items.findById(command.itemId) ?: throw ResourceNotFound("Item", command.itemId.toString())
        if (!vendors.exists(command.vendorItem.vendorId)) throw UnknownVendor(command.vendorItem.vendorId)

        item.setVendorItem(command.vendorItem, clock)
        items.save(item)
        item.releaseEvents().forEach(events::publishEvent)
    }
}

/** Queries: items by identifier and by page. */
@Service
@Transactional(readOnly = true)
class ItemQueries(
    private val items: ItemRepository,
) {
    fun find(id: ItemId): Item = items.findById(id) ?: throw ResourceNotFound("Item", id.toString())

    fun list(query: PageQuery): Page<Item> = items.findPage(query)
}
