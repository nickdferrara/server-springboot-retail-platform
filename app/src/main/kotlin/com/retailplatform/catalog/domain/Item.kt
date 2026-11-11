package com.retailplatform.catalog.domain

import com.retailplatform.catalog.api.events.ItemChanged
import com.retailplatform.catalog.api.events.ItemCreated
import com.retailplatform.catalog.api.events.ItemDiscontinued
import com.retailplatform.catalog.api.events.ItemFacts
import com.retailplatform.catalog.api.events.VendorCostChanged
import com.retailplatform.shared.AggregateRoot
import com.retailplatform.shared.BusinessDate
import com.retailplatform.shared.BusinessRuleViolation
import com.retailplatform.shared.EventId
import com.retailplatform.shared.ItemId
import com.retailplatform.shared.Quantity
import java.time.Clock

/** The parts of an item that merchandising can change. */
data class ItemDetails(
    val description: ItemDescription,
    val category: ItemCategory,
    val units: ItemUnits,
    val upcs: Set<Upc>,
)

/** Something the business stocks and sells. */
@Suppress("LongParameterList")
class Item private constructor(
    val id: ItemId,
    val number: ItemNumber,
    details: ItemDetails,
    vendorItems: List<VendorItem>,
    discontinuedOn: BusinessDate?,
    val version: Int,
) : AggregateRoot() {
    var details: ItemDetails = details
        private set
    var vendorItems: List<VendorItem> = vendorItems.toList()
        private set

    /** The last day the item is bought or sold, once it has been discontinued. */
    var discontinuedOn: BusinessDate? = discontinuedOn
        private set

    val isDiscontinued: Boolean get() = discontinuedOn != null

    /**
     * Replaces the description, category, packs, and barcodes, raising [ItemChanged] unless nothing differs.
     *
     * @throws ItemIsDiscontinued if the item has been discontinued.
     * @throws BaseUnitCannotChange if [newDetails] has a different base unit.
     */
    fun change(
        newDetails: ItemDetails,
        clock: Clock,
    ) {
        if (isDiscontinued) throw ItemIsDiscontinued(number)
        if (newDetails.units.baseUnit != details.units.baseUnit) throw BaseUnitCannotChange(number)
        if (newDetails == details) return
        details = newDetails
        raise(ItemChanged(EventId.generate(clock), clock.instant(), BusinessDate.today(clock), id, facts()))
    }

    /**
     * Stops the item being bought or sold after [lastDay], raising [ItemDiscontinued].
     *
     * @throws ItemIsDiscontinued if it has already been discontinued.
     */
    fun discontinue(
        lastDay: BusinessDate,
        clock: Clock,
    ) {
        if (isDiscontinued) throw ItemIsDiscontinued(number)
        discontinuedOn = lastDay
        raise(ItemDiscontinued(EventId.generate(clock), clock.instant(), lastDay, id, number.value))
    }

    /**
     * Assigns a vendor to the item or replaces how that vendor supplies it. Raises [VendorCostChanged] when the
     * vendor is new to the item or its list cost differs.
     *
     * @throws ItemIsDiscontinued if the item has been discontinued.
     */
    fun setVendorItem(
        vendorItem: VendorItem,
        clock: Clock,
    ) {
        if (isDiscontinued) throw ItemIsDiscontinued(number)
        val previous = vendorItems.find { it.vendorId == vendorItem.vendorId }
        vendorItems = vendorItems.filterNot { it.vendorId == vendorItem.vendorId } + vendorItem
        if (previous?.listCost != vendorItem.listCost) {
            raise(
                VendorCostChanged(
                    eventId = EventId.generate(clock),
                    occurredAt = clock.instant(),
                    businessDate = BusinessDate.today(clock),
                    itemId = id,
                    vendorId = vendorItem.vendorId,
                    vendorSku = vendorItem.vendorSku,
                    casePack = vendorItem.casePack,
                    previousCost = previous?.listCost?.amount,
                    newCost = vendorItem.listCost.amount,
                    currency = vendorItem.listCost.currency.currencyCode,
                ),
            )
        }
    }

    /** Converts [quantity] in any of the item's units to its base unit. */
    fun toBaseUnits(quantity: Quantity): Quantity = details.units.toBaseUnits(quantity)

    private fun facts() =
        ItemFacts(
            itemNumber = number.value,
            description = details.description.value,
            departmentCode = details.category.departmentCode.value,
            categoryCode = details.category.code.value,
            baseUnit = details.units.baseUnit.code,
            upcs = details.upcs.map { it.value }.sorted(),
        )

    companion object {
        /** Adds a new item to the catalog, raising [ItemCreated]. */
        fun create(
            number: ItemNumber,
            details: ItemDetails,
            clock: Clock,
        ): Item {
            val item = Item(ItemId.generate(clock), number, details, emptyList(), discontinuedOn = null, version = 0)
            item.raise(
                ItemCreated(EventId.generate(clock), clock.instant(), BusinessDate.today(clock), item.id, item.facts()),
            )
            return item
        }

        /** Rebuilds an item from stored state. Raises no events. */
        fun rehydrate(
            id: ItemId,
            number: ItemNumber,
            details: ItemDetails,
            vendorItems: List<VendorItem>,
            discontinuedOn: BusinessDate?,
            version: Int,
        ): Item = Item(id, number, details, vendorItems, discontinuedOn, version)
    }
}

class ItemNumberAlreadyInUse(
    number: ItemNumber,
) : BusinessRuleViolation("catalog.item-number-in-use", "Item number $number is already in use")

class UpcAlreadyAssigned(
    upcs: Collection<Upc>,
) : BusinessRuleViolation(
        "catalog.upc-already-assigned",
        "Already assigned to another item: ${upcs.joinToString()}",
    )

class ItemIsDiscontinued(
    number: ItemNumber,
) : BusinessRuleViolation("catalog.item-discontinued", "Item $number has been discontinued")

class BaseUnitCannotChange(
    number: ItemNumber,
) : BusinessRuleViolation(
        "catalog.base-unit-cannot-change",
        "The base unit of item $number cannot change once the item exists",
    )

class UnknownVendor(
    vendorId: Any,
) : BusinessRuleViolation("catalog.unknown-vendor", "Vendor $vendorId does not exist")
