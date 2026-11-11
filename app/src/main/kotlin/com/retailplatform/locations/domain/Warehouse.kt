package com.retailplatform.locations.domain

import com.retailplatform.shared.BusinessRuleViolation
import com.retailplatform.shared.WarehouseId
import java.time.Clock
import java.time.ZoneId

/** The number the business knows a warehouse by: one to six digits, kept as text. */
@JvmInline
value class WarehouseNumber private constructor(
    val value: String,
) {
    override fun toString(): String = value

    companion object {
        private val FORMAT = Regex("[0-9]{1,6}")

        /** @throws IllegalArgumentException unless [value] is one to six digits. */
        fun of(value: String): WarehouseNumber {
            require(FORMAT.matches(value)) { "Not a warehouse number: '$value'" }
            return WarehouseNumber(value)
        }
    }
}

/** A warehouse that stocks product and ships it to stores. */
class Warehouse private constructor(
    val id: WarehouseId,
    val number: WarehouseNumber,
    name: LocationName,
    timeZone: ZoneId,
    val version: Int,
) {
    var name: LocationName = name
        private set
    var timeZone: ZoneId = timeZone
        private set

    fun updateDetails(
        name: LocationName,
        timeZone: ZoneId,
    ) {
        this.name = name
        this.timeZone = timeZone
    }

    companion object {
        fun create(
            number: WarehouseNumber,
            name: LocationName,
            timeZone: ZoneId,
            clock: Clock,
        ): Warehouse = Warehouse(WarehouseId.generate(clock), number, name, timeZone, version = 0)

        /** Rebuilds a warehouse from stored state. */
        fun rehydrate(
            id: WarehouseId,
            number: WarehouseNumber,
            name: LocationName,
            timeZone: ZoneId,
            version: Int,
        ): Warehouse = Warehouse(id, number, name, timeZone, version)
    }
}

class WarehouseNumberAlreadyInUse(
    number: WarehouseNumber,
) : BusinessRuleViolation("locations.warehouse-number-in-use", "Warehouse number $number is already in use")
