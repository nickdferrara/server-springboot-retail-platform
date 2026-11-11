package com.retailplatform.locations.domain

import com.retailplatform.locations.api.events.StoreClosed
import com.retailplatform.locations.api.events.StoreOpened
import com.retailplatform.shared.AggregateRoot
import com.retailplatform.shared.BusinessDate
import com.retailplatform.shared.BusinessRuleViolation
import com.retailplatform.shared.EventId
import com.retailplatform.shared.StoreId
import java.time.Clock
import java.time.ZoneId

/** A retail store. */
class Store private constructor(
    val id: StoreId,
    val number: StoreNumber,
    name: LocationName,
    timeZone: ZoneId,
    val openedOn: BusinessDate,
    closedOn: BusinessDate?,
    val version: Int,
) : AggregateRoot() {
    var name: LocationName = name
        private set
    var timeZone: ZoneId = timeZone
        private set

    /** The last trading day, once the store has closed. */
    var closedOn: BusinessDate? = closedOn
        private set

    val isOpen: Boolean get() = closedOn == null

    fun updateDetails(
        name: LocationName,
        timeZone: ZoneId,
    ) {
        this.name = name
        this.timeZone = timeZone
    }

    /**
     * Closes the store, raising [StoreClosed].
     *
     * @throws StoreAlreadyClosed if it has already closed.
     * @throws StoreClosedBeforeOpening if [lastTradingDay] is before the day it opened.
     */
    fun close(
        lastTradingDay: BusinessDate,
        clock: Clock,
    ) {
        if (!isOpen) throw StoreAlreadyClosed(number)
        if (lastTradingDay < openedOn) throw StoreClosedBeforeOpening(number, openedOn)
        closedOn = lastTradingDay
        raise(
            StoreClosed(
                eventId = EventId.generate(clock),
                occurredAt = clock.instant(),
                businessDate = lastTradingDay,
                storeId = id,
                storeNumber = number.value,
            ),
        )
    }

    companion object {
        /** Opens a new store, raising [StoreOpened]. */
        fun open(
            number: StoreNumber,
            name: LocationName,
            timeZone: ZoneId,
            openedOn: BusinessDate,
            clock: Clock,
        ): Store {
            val store = Store(StoreId.generate(clock), number, name, timeZone, openedOn, closedOn = null, version = 0)
            store.raise(
                StoreOpened(
                    eventId = EventId.generate(clock),
                    occurredAt = clock.instant(),
                    businessDate = openedOn,
                    storeId = store.id,
                    storeNumber = number.value,
                    storeName = name.value,
                    timeZone = timeZone.id,
                ),
            )
            return store
        }

        /** Rebuilds a store from stored state. Raises no events. */
        @Suppress("LongParameterList")
        fun rehydrate(
            id: StoreId,
            number: StoreNumber,
            name: LocationName,
            timeZone: ZoneId,
            openedOn: BusinessDate,
            closedOn: BusinessDate?,
            version: Int,
        ): Store = Store(id, number, name, timeZone, openedOn, closedOn, version)
    }
}

class StoreAlreadyClosed(
    number: StoreNumber,
) : BusinessRuleViolation("locations.store-already-closed", "Store $number has already closed")

class StoreClosedBeforeOpening(
    number: StoreNumber,
    openedOn: BusinessDate,
) : BusinessRuleViolation(
        "locations.store-closed-before-opening",
        "Store $number opened on $openedOn and cannot close before that",
    )

class StoreStillOnRoute(
    number: StoreNumber,
) : BusinessRuleViolation(
        "locations.store-still-on-route",
        "Store $number is still a stop on a delivery route; remove it from the route before closing",
    )
