package com.retailplatform.locations

import com.retailplatform.TestcontainersConfiguration
import com.retailplatform.locations.api.events.RouteScheduleChanged
import com.retailplatform.locations.api.events.StoreClosed
import com.retailplatform.locations.api.events.StoreOpened
import com.retailplatform.locations.application.ChangeRouteSchedule
import com.retailplatform.locations.application.ChangeRouteScheduleCommand
import com.retailplatform.locations.application.CloseStore
import com.retailplatform.locations.application.CloseStoreCommand
import com.retailplatform.locations.application.CreateRoute
import com.retailplatform.locations.application.CreateRouteCommand
import com.retailplatform.locations.application.CreateWarehouse
import com.retailplatform.locations.application.CreateWarehouseCommand
import com.retailplatform.locations.application.OpenStore
import com.retailplatform.locations.application.OpenStoreCommand
import com.retailplatform.locations.application.RouteQueries
import com.retailplatform.locations.application.StoreQueries
import com.retailplatform.locations.application.UpdateStoreDetails
import com.retailplatform.locations.application.UpdateStoreDetailsCommand
import com.retailplatform.locations.application.UpdateWarehouseDetails
import com.retailplatform.locations.application.UpdateWarehouseDetailsCommand
import com.retailplatform.locations.application.WarehouseQueries
import com.retailplatform.locations.domain.LocationName
import com.retailplatform.locations.domain.OrderCutoff
import com.retailplatform.locations.domain.RouteCode
import com.retailplatform.locations.domain.RouteCodeAlreadyInUse
import com.retailplatform.locations.domain.RouteSchedule
import com.retailplatform.locations.domain.RouteStop
import com.retailplatform.locations.domain.RouteStopAtUnavailableStore
import com.retailplatform.locations.domain.StoreNumber
import com.retailplatform.locations.domain.StoreNumberAlreadyInUse
import com.retailplatform.locations.domain.StoreStillOnRoute
import com.retailplatform.locations.domain.WarehouseNumber
import com.retailplatform.locations.domain.WarehouseNumberAlreadyInUse
import com.retailplatform.platform.security.Roles
import com.retailplatform.shared.BusinessDate
import com.retailplatform.shared.PageQuery
import com.retailplatform.shared.ResourceNotFound
import com.retailplatform.shared.RouteId
import com.retailplatform.shared.StoreId
import com.retailplatform.shared.WarehouseId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.modulith.test.ApplicationModuleTest
import org.springframework.modulith.test.Scenario
import org.springframework.security.test.context.support.WithMockUser
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId

@ApplicationModuleTest
@Import(TestcontainersConfiguration::class)
@WithMockUser(authorities = [Roles.MASTER_DATA_EDITOR])
class LocationsModuleTest(
    @Autowired private val openStore: OpenStore,
    @Autowired private val updateStoreDetails: UpdateStoreDetails,
    @Autowired private val closeStore: CloseStore,
    @Autowired private val stores: StoreQueries,
    @Autowired private val createWarehouse: CreateWarehouse,
    @Autowired private val updateWarehouseDetails: UpdateWarehouseDetails,
    @Autowired private val warehouses: WarehouseQueries,
    @Autowired private val createRoute: CreateRoute,
    @Autowired private val changeRouteSchedule: ChangeRouteSchedule,
    @Autowired private val routes: RouteQueries,
    @Autowired private val clock: Clock,
) {
    private val chicago = ZoneId.of("America/Chicago")
    private val firstDay = BusinessDate.parse("2026-12-01")

    @Test
    fun `opening a store saves it and publishes StoreOpened`(scenario: Scenario) {
        val command = storeCommand(number = "0201", name = "Harbor View")

        scenario
            .stimulate(Runnable { openStore.handle(command) })
            .andWaitForEventOfType(StoreOpened::class.java)
            .matching { it.storeNumber == "0201" }
            .toArriveAndVerify { event ->
                event.storeName shouldBe "Harbor View"
                event.businessDate shouldBe command.openedOn

                val saved = stores.find(event.storeId)
                saved.number shouldBe command.number
                saved.timeZone shouldBe command.timeZone
                saved.isOpen shouldBe true
            }
    }

    @Test
    fun `a store number can be used only once`() {
        openStore.handle(storeCommand(number = "0202", name = "First"))

        shouldThrow<StoreNumberAlreadyInUse> { openStore.handle(storeCommand(number = "0202", name = "Second")) }
    }

    @Test
    fun `a store's details can be changed and it appears in the list`() {
        val storeId = openStore.handle(storeCommand(number = "0203", name = "Before"))

        updateStoreDetails.handle(
            UpdateStoreDetailsCommand(storeId, LocationName.of("After"), ZoneId.of("America/New_York")),
        )

        val saved = stores.find(storeId)
        saved.name.value shouldBe "After"
        saved.version shouldBe 2
        stores.list(PageQuery.of(size = 200)).items.map { it.id } shouldContain storeId
    }

    @Test
    fun `closing a store publishes StoreClosed`(scenario: Scenario) {
        val storeId = openStore.handle(storeCommand(number = "0204", name = "Closing"))

        scenario
            .stimulate(Runnable { closeStore.handle(CloseStoreCommand(storeId, firstDay.plusDays(10))) })
            .andWaitForEventOfType(StoreClosed::class.java)
            .matching { it.storeId == storeId }
            .toArriveAndVerify { event ->
                event.businessDate shouldBe firstDay.plusDays(10)
                stores.find(storeId).isOpen shouldBe false
            }
    }

    @Test
    fun `a warehouse is created, changed, and listed`() {
        val warehouseId = createWarehouse.handle(warehouseCommand("0901"))

        updateWarehouseDetails.handle(
            UpdateWarehouseDetailsCommand(warehouseId, LocationName.of("Renamed DC"), chicago),
        )

        warehouses.find(warehouseId).name.value shouldBe "Renamed DC"
        warehouses.list(PageQuery.of(size = 200)).items.map { it.id } shouldContain warehouseId
        shouldThrow<WarehouseNumberAlreadyInUse> { createWarehouse.handle(warehouseCommand("0901")) }
    }

    @Test
    fun `creating a route publishes its schedule, and changing the schedule publishes it again`(scenario: Scenario) {
        val warehouseId = createWarehouse.handle(warehouseCommand("0902"))
        val storeA = openStore.handle(storeCommand(number = "0211", name = "Route A"))
        val storeB = openStore.handle(storeCommand(number = "0212", name = "Route B"))
        val schedule = RouteSchedule.of(listOf(stop(storeA, DayOfWeek.MONDAY), stop(storeB, DayOfWeek.MONDAY)))
        var routeId: RouteId? = null

        scenario
            .stimulate(
                Runnable {
                    routeId =
                        createRoute.handle(
                            CreateRouteCommand(RouteCode.of("R0902"), LocationName.of("Loop"), warehouseId, schedule),
                        )
                },
            ).andWaitForEventOfType(RouteScheduleChanged::class.java)
            .matching { it.routeCode == "R0902" }
            .toArriveAndVerify { event ->
                event.stops.map { it.storeId } shouldBe listOf(storeA, storeB)
                routes.find(event.routeId).schedule shouldBe schedule
            }

        val created = requireNotNull(routeId)
        val thursdayOnly = RouteSchedule.of(listOf(stop(storeB, DayOfWeek.THURSDAY)))
        scenario
            .stimulate(Runnable { changeRouteSchedule.handle(ChangeRouteScheduleCommand(created, thursdayOnly)) })
            .andWaitForEventOfType(RouteScheduleChanged::class.java)
            .matching { it.routeId == created && it.stops.size == 1 }
            .toArriveAndVerify { event ->
                event.stops.single().deliveryDay shouldBe DayOfWeek.THURSDAY
                routes.find(created).schedule shouldBe thursdayOnly
                routes.list(PageQuery.of(size = 200)).items.map { it.id } shouldContain created
            }
    }

    @Test
    fun `a route can only stop at stores that exist and are open`() {
        val warehouseId = createWarehouse.handle(warehouseCommand("0903"))
        val closed = openStore.handle(storeCommand(number = "0221", name = "Closed"))
        closeStore.handle(CloseStoreCommand(closed, firstDay))
        val unknown = StoreId.generate(clock)

        listOf(closed, unknown).forEach { storeId ->
            shouldThrow<RouteStopAtUnavailableStore> {
                createRoute.handle(
                    CreateRouteCommand(
                        RouteCode.of("R0903"),
                        LocationName.of("Bad"),
                        warehouseId,
                        RouteSchedule.of(listOf(stop(storeId, DayOfWeek.MONDAY))),
                    ),
                )
            }
        }
    }

    @Test
    fun `a store on a route cannot close until it is taken off the route`() {
        val warehouseId = createWarehouse.handle(warehouseCommand("0904"))
        val storeId = openStore.handle(storeCommand(number = "0231", name = "On route"))
        val routeId =
            createRoute.handle(
                CreateRouteCommand(
                    RouteCode.of("R0904"),
                    LocationName.of("Loop"),
                    warehouseId,
                    RouteSchedule.of(listOf(stop(storeId, DayOfWeek.FRIDAY))),
                ),
            )

        shouldThrow<StoreStillOnRoute> { closeStore.handle(CloseStoreCommand(storeId, firstDay)) }

        changeRouteSchedule.handle(ChangeRouteScheduleCommand(routeId, RouteSchedule.EMPTY))
        closeStore.handle(CloseStoreCommand(storeId, firstDay))
        stores.find(storeId).isOpen shouldBe false
    }

    @Test
    fun `route codes are unique and a route needs an existing warehouse`() {
        val warehouseId = createWarehouse.handle(warehouseCommand("0905"))
        val command =
            CreateRouteCommand(RouteCode.of("R0905"), LocationName.of("Loop"), warehouseId, RouteSchedule.EMPTY)
        createRoute.handle(command)

        shouldThrow<RouteCodeAlreadyInUse> { createRoute.handle(command) }
        shouldThrow<ResourceNotFound> {
            createRoute.handle(command.copy(code = RouteCode.of("R0906"), warehouseId = WarehouseId.generate(clock)))
        }
    }

    @Test
    fun `things that do not exist cannot be found or changed`() {
        val storeId = StoreId.generate(clock)
        val warehouseId = WarehouseId.generate(clock)
        val routeId = RouteId.generate(clock)
        val name = LocationName.of("Nobody")

        shouldThrow<ResourceNotFound> { stores.find(storeId) }
        shouldThrow<ResourceNotFound> { updateStoreDetails.handle(UpdateStoreDetailsCommand(storeId, name, chicago)) }
        shouldThrow<ResourceNotFound> { closeStore.handle(CloseStoreCommand(storeId, firstDay)) }
        shouldThrow<ResourceNotFound> { warehouses.find(warehouseId) }
        shouldThrow<ResourceNotFound> {
            updateWarehouseDetails.handle(UpdateWarehouseDetailsCommand(warehouseId, name, chicago))
        }
        shouldThrow<ResourceNotFound> { routes.find(routeId) }
        shouldThrow<ResourceNotFound> {
            changeRouteSchedule.handle(ChangeRouteScheduleCommand(routeId, RouteSchedule.EMPTY))
        }
    }

    private fun stop(
        storeId: StoreId,
        day: DayOfWeek,
    ) = RouteStop(storeId, day, OrderCutoff.of(1, LocalTime.of(14, 0)))

    private fun warehouseCommand(number: String) =
        CreateWarehouseCommand(WarehouseNumber.of(number), LocationName.of("DC $number"), chicago)

    private fun storeCommand(
        number: String,
        name: String,
    ) = OpenStoreCommand(
        number = StoreNumber.of(number),
        name = LocationName.of(name),
        timeZone = chicago,
        openedOn = firstDay,
    )
}
