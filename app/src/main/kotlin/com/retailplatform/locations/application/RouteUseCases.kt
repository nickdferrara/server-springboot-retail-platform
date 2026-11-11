package com.retailplatform.locations.application

import com.retailplatform.locations.domain.DeliveryRoute
import com.retailplatform.locations.domain.LocationName
import com.retailplatform.locations.domain.RouteCode
import com.retailplatform.locations.domain.RouteCodeAlreadyInUse
import com.retailplatform.locations.domain.RouteSchedule
import com.retailplatform.locations.domain.RouteStopAtUnavailableStore
import com.retailplatform.platform.security.RequiresRole
import com.retailplatform.platform.security.Roles
import com.retailplatform.shared.Page
import com.retailplatform.shared.PageQuery
import com.retailplatform.shared.ResourceNotFound
import com.retailplatform.shared.RouteId
import com.retailplatform.shared.WarehouseId
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/** Policy: every stop on a schedule must be at a store that exists and is open. */
@Component
class RouteStopPolicy(
    private val stores: StoreRepository,
) {
    /** @throws RouteStopAtUnavailableStore naming the stores that are closed or unknown. */
    fun check(schedule: RouteSchedule) {
        val open =
            stores
                .findAllById(schedule.storeIds)
                .filter { it.isOpen }
                .map { it.id }
                .toSet()
        val unavailable = schedule.storeIds - open
        if (unavailable.isNotEmpty()) throw RouteStopAtUnavailableStore(unavailable)
    }
}

data class CreateRouteCommand(
    val code: RouteCode,
    val name: LocationName,
    val warehouseId: WarehouseId,
    val schedule: RouteSchedule,
)

/** Use case: set up a delivery route from a warehouse. */
@Service
class CreateRoute(
    private val routes: DeliveryRouteRepository,
    private val warehouses: WarehouseRepository,
    private val stopPolicy: RouteStopPolicy,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
) {
    @RequiresRole(Roles.MASTER_DATA_EDITOR)
    @Transactional
    fun handle(command: CreateRouteCommand): RouteId {
        if (routes.existsByCode(command.code)) throw RouteCodeAlreadyInUse(command.code)
        warehouses.findById(command.warehouseId)
            ?: throw ResourceNotFound("Warehouse", command.warehouseId.toString())
        stopPolicy.check(command.schedule)

        val route = DeliveryRoute.create(command.code, command.name, command.warehouseId, command.schedule, clock)
        routes.save(route)
        route.releaseEvents().forEach(events::publishEvent)
        return route.id
    }
}

data class ChangeRouteScheduleCommand(
    val routeId: RouteId,
    val schedule: RouteSchedule,
)

/** Use case: replace a route's stops and order cutoffs. */
@Service
class ChangeRouteSchedule(
    private val routes: DeliveryRouteRepository,
    private val stopPolicy: RouteStopPolicy,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
) {
    @RequiresRole(Roles.MASTER_DATA_EDITOR)
    @Transactional
    fun handle(command: ChangeRouteScheduleCommand) {
        val route = routes.findById(command.routeId) ?: throw ResourceNotFound("Route", command.routeId.toString())
        stopPolicy.check(command.schedule)

        route.changeSchedule(command.schedule, clock)
        routes.save(route)
        route.releaseEvents().forEach(events::publishEvent)
    }
}

/** Queries: routes by identifier and by page. */
@Service
@Transactional(readOnly = true)
class RouteQueries(
    private val routes: DeliveryRouteRepository,
) {
    fun find(id: RouteId): DeliveryRoute = routes.findById(id) ?: throw ResourceNotFound("Route", id.toString())

    fun list(query: PageQuery): Page<DeliveryRoute> = routes.findPage(query)
}
