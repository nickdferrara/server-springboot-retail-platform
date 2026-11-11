package com.retailplatform.locations.domain

import com.retailplatform.locations.api.events.StoreOpened
import com.retailplatform.shared.BusinessDate
import com.retailplatform.shared.EventId
import com.retailplatform.shared.StoreId
import java.time.Clock
import java.time.ZoneId

/** A retail store. */
class Store private constructor(
    val id: StoreId,
    val number: StoreNumber,
    val name: StoreName,
    val timeZone: ZoneId,
    val openedOn: BusinessDate,
    events: List<StoreOpened>,
) {
    private val pendingEvents = events.toMutableList()

    /** Hands over the events raised since the store was loaded or created, so each is published once. */
    fun releaseEvents(): List<StoreOpened> = pendingEvents.toList().also { pendingEvents.clear() }

    companion object {
        /** Opens a new store, raising [StoreOpened]. */
        fun open(
            number: StoreNumber,
            name: StoreName,
            timeZone: ZoneId,
            openedOn: BusinessDate,
            clock: Clock,
        ): Store {
            val id = StoreId.generate(clock)
            val opened =
                StoreOpened(
                    eventId = EventId.generate(clock),
                    occurredAt = clock.instant(),
                    businessDate = openedOn,
                    storeId = id,
                    storeNumber = number.value,
                    storeName = name.value,
                    timeZone = timeZone.id,
                )
            return Store(id, number, name, timeZone, openedOn, listOf(opened))
        }

        /** Rebuilds a store from stored state. Raises no events. */
        fun rehydrate(
            id: StoreId,
            number: StoreNumber,
            name: StoreName,
            timeZone: ZoneId,
            openedOn: BusinessDate,
        ): Store = Store(id, number, name, timeZone, openedOn, emptyList())
    }
}
