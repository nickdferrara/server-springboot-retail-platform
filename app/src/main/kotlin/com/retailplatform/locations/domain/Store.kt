package com.retailplatform.locations.domain

import com.retailplatform.locations.api.events.StoreOpened
import com.retailplatform.shared.AggregateRoot
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
) : AggregateRoot() {
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
            return Store(id, number, name, timeZone, openedOn).also { it.raise(opened) }
        }

        /** Rebuilds a store from stored state. Raises no events. */
        fun rehydrate(
            id: StoreId,
            number: StoreNumber,
            name: StoreName,
            timeZone: ZoneId,
            openedOn: BusinessDate,
        ): Store = Store(id, number, name, timeZone, openedOn)
    }
}
