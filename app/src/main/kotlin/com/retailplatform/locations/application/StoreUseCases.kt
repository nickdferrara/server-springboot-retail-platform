package com.retailplatform.locations.application

import com.retailplatform.locations.api.StoreDirectory
import com.retailplatform.locations.domain.LocationName
import com.retailplatform.locations.domain.Store
import com.retailplatform.locations.domain.StoreNumber
import com.retailplatform.locations.domain.StoreNumberAlreadyInUse
import com.retailplatform.locations.domain.StoreStillOnRoute
import com.retailplatform.platform.security.RequiresRole
import com.retailplatform.platform.security.Roles
import com.retailplatform.shared.BusinessDate
import com.retailplatform.shared.Page
import com.retailplatform.shared.PageQuery
import com.retailplatform.shared.ResourceNotFound
import com.retailplatform.shared.StoreId
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.ZoneId

data class OpenStoreCommand(
    val number: StoreNumber,
    val name: LocationName,
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
    @RequiresRole(Roles.MASTER_DATA_EDITOR)
    @Transactional
    fun handle(command: OpenStoreCommand): StoreId {
        if (stores.existsByNumber(command.number)) throw StoreNumberAlreadyInUse(command.number)

        val store = Store.open(command.number, command.name, command.timeZone, command.openedOn, clock)
        stores.save(store)
        store.releaseEvents().forEach(events::publishEvent)
        return store.id
    }
}

data class UpdateStoreDetailsCommand(
    val storeId: StoreId,
    val name: LocationName,
    val timeZone: ZoneId,
)

/** Use case: change a store's name and time zone. */
@Service
class UpdateStoreDetails(
    private val stores: StoreRepository,
) {
    @RequiresRole(Roles.MASTER_DATA_EDITOR)
    @Transactional
    fun handle(command: UpdateStoreDetailsCommand) {
        val store = stores.findById(command.storeId) ?: throw ResourceNotFound("Store", command.storeId.toString())

        store.updateDetails(command.name, command.timeZone)
        stores.save(store)
    }
}

data class CloseStoreCommand(
    val storeId: StoreId,
    val lastTradingDay: BusinessDate,
)

/** Use case: close a store. A store that is still a stop on a delivery route cannot close. */
@Service
class CloseStore(
    private val stores: StoreRepository,
    private val routes: DeliveryRouteRepository,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
) {
    @RequiresRole(Roles.MASTER_DATA_EDITOR)
    @Transactional
    fun handle(command: CloseStoreCommand) {
        val store = stores.findById(command.storeId) ?: throw ResourceNotFound("Store", command.storeId.toString())
        if (routes.existsStopAt(store.id)) throw StoreStillOnRoute(store.number)

        store.close(command.lastTradingDay, clock)
        stores.save(store)
        store.releaseEvents().forEach(events::publishEvent)
    }
}

/** Queries: stores by identifier and by page; also answers other modules through [StoreDirectory]. */
@Service
@Transactional(readOnly = true)
class StoreQueries(
    private val stores: StoreRepository,
) : StoreDirectory {
    fun find(id: StoreId): Store = stores.findById(id) ?: throw ResourceNotFound("Store", id.toString())

    fun list(query: PageQuery): Page<Store> = stores.findPage(query)

    override fun exists(storeId: StoreId): Boolean = stores.findById(storeId) != null
}
