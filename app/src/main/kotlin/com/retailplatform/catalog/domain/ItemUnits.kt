package com.retailplatform.catalog.domain

import com.retailplatform.shared.BusinessRuleViolation
import com.retailplatform.shared.Quantity
import com.retailplatform.shared.UnitOfMeasure
import java.math.BigDecimal
import java.math.RoundingMode

/** A larger unit an item comes in: one [unit] holds [baseUnits] of the item's base unit, for example CS = 24 EA. */
@ConsistentCopyVisibility
data class Pack private constructor(
    val unit: UnitOfMeasure,
    val baseUnits: BigDecimal,
) {
    companion object {
        /** @throws IllegalArgumentException unless [baseUnits] is above zero with at most three decimal places. */
        fun of(
            unit: UnitOfMeasure,
            baseUnits: BigDecimal,
        ): Pack {
            require(baseUnits.signum() > 0) { "A pack holds more than zero base units: $baseUnits" }
            require(baseUnits.stripTrailingZeros().scale() <= Quantity.SCALE) {
                "A pack size has at most ${Quantity.SCALE} decimal places: $baseUnits"
            }
            return Pack(unit, baseUnits.setScale(Quantity.SCALE))
        }
    }
}

/**
 * The units an item is counted in: its base unit (the unit it is stocked and sold in) and the packs that convert
 * to it.
 */
@ConsistentCopyVisibility
data class ItemUnits private constructor(
    val baseUnit: UnitOfMeasure,
    val packs: List<Pack>,
) {
    val all: Set<UnitOfMeasure> get() = packs.map { it.unit }.toSet() + baseUnit

    /**
     * Converts [quantity] to the base unit.
     *
     * @throws UnitNotDefinedForItem if the quantity's unit is neither the base unit nor one of the packs.
     */
    fun toBaseUnits(quantity: Quantity): Quantity {
        if (quantity.unit == baseUnit) return quantity
        val pack = packs.find { it.unit == quantity.unit } ?: throw UnitNotDefinedForItem(quantity.unit)
        val converted = (quantity.amount * pack.baseUnits).setScale(Quantity.SCALE, RoundingMode.HALF_EVEN)
        return Quantity.of(converted, baseUnit)
    }

    companion object {
        /** @throws IllegalArgumentException if a pack repeats a unit or uses the base unit. */
        fun of(
            baseUnit: UnitOfMeasure,
            packs: List<Pack> = emptyList(),
        ): ItemUnits {
            val units = packs.map { it.unit }
            require(baseUnit !in units) { "A pack cannot use the base unit $baseUnit" }
            require(units.size == units.toSet().size) { "Each pack unit can be defined once per item" }
            return ItemUnits(baseUnit, packs.toList())
        }
    }
}

class UnitNotDefinedForItem(
    unit: UnitOfMeasure,
) : BusinessRuleViolation("catalog.unit-not-defined-for-item", "The item has no pack in unit $unit")

class UnknownUnitOfMeasure(
    units: Collection<UnitOfMeasure>,
) : BusinessRuleViolation(
        "catalog.unknown-unit-of-measure",
        "Not a defined unit of measure: ${units.joinToString()}",
    )
