package com.retailplatform.locations.application

import com.retailplatform.locations.domain.Store
import com.retailplatform.locations.domain.StoreName
import com.retailplatform.locations.domain.StoreNumber
import com.retailplatform.locations.domain.StoreNumberAlreadyInUse
import com.retailplatform.shared.BusinessDate
import com.retailplatform.shared.StoreId
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.ZoneId

data class OpenStoreCommand(
    val number: StoreNumber,
    val name: StoreName,
    val timeZone: ZoneId,
    val openedOn: BusinessDate,
)

/** Use case: open a new store. */
@Service
class OpenStore(
    private val stores: StoreRepository,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
) {
    @Transactional
    fun handle(command: OpenStoreCommand): StoreId {
        if (stores.existsByNumber(command.number)) throw StoreNumberAlreadyInUse(command.number)

        val store = Store.open(command.number, command.name, command.timeZone, command.openedOn, clock)
        stores.add(store)
        store.releaseEvents().forEach(events::publishEvent)
        return store.id
    }
}
