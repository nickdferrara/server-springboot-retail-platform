package com.retailplatform.locations.domain

import com.retailplatform.locations.api.events.RouteScheduleChanged
import com.retailplatform.shared.StoreId
import com.retailplatform.shared.WarehouseId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

class DeliveryRouteTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-10T15:00:00Z"), ZoneOffset.UTC)
    private val warehouseId = WarehouseId.generate(clock)
    private val storeA = StoreId.generate(clock)
    private val storeB = StoreId.generate(clock)
    private val noonTheDayBefore = OrderCutoff.of(1, LocalTime.NOON)

    private fun stop(
        store: StoreId,
        day: DayOfWeek,
    ) = RouteStop(store, day, noonTheDayBefore)

    private fun route(schedule: RouteSchedule = RouteSchedule.EMPTY) =
        DeliveryRoute.create(RouteCode.of("NORTH1"), LocationName.of("North loop"), warehouseId, schedule, clock)

    @Test
    fun `creating a route announces its schedule with stops numbered in truck order`() {
        val schedule = RouteSchedule.of(listOf(stop(storeB, DayOfWeek.TUESDAY), stop(storeA, DayOfWeek.TUESDAY)))

        val event = route(schedule).releaseEvents().single() as RouteScheduleChanged

        event.routeCode shouldBe "NORTH1"
        event.warehouseId shouldBe warehouseId
        event.stops.map { it.storeId to it.sequence } shouldBe listOf(storeB to 1, storeA to 2)
        event.stops.first().cutoffDaysBefore shouldBe 1
        event.stops.first().cutoffTime shouldBe LocalTime.NOON
    }

    @Test
    fun `changing the schedule raises an event only when it differs`() {
        val route = route().also { it.releaseEvents() }
        val schedule = RouteSchedule.of(listOf(stop(storeA, DayOfWeek.MONDAY), stop(storeA, DayOfWeek.THURSDAY)))

        route.changeSchedule(schedule, clock)
        route.changeSchedule(schedule, clock)

        (route.releaseEvents().single() as RouteScheduleChanged).stops.size shouldBe 2
        route.schedule shouldBe schedule
    }

    @Test
    fun `a store can be delivered on several weekdays but once per weekday`() {
        RouteSchedule.of(listOf(stop(storeA, DayOfWeek.MONDAY), stop(storeA, DayOfWeek.FRIDAY))).storeIds shouldBe
            setOf(storeA)
        shouldThrow<IllegalArgumentException> {
            RouteSchedule.of(listOf(stop(storeA, DayOfWeek.MONDAY), stop(storeA, DayOfWeek.MONDAY)))
        }
    }

    @Test
    fun `an order cutoff is at most six days before delivery`() {
        OrderCutoff.of(0, LocalTime.of(5, 30)).daysBefore shouldBe 0
        shouldThrow<IllegalArgumentException> { OrderCutoff.of(7, LocalTime.NOON) }
        shouldThrow<IllegalArgumentException> { OrderCutoff.of(-1, LocalTime.NOON) }
    }

    @Test
    fun `a rebuilt route raises nothing, and codes are validated`() {
        val original = route()
        val rebuilt =
            DeliveryRoute.rehydrate(original.id, original.code, original.name, warehouseId, RouteSchedule.EMPTY, 2)

        rebuilt.releaseEvents().shouldBeEmpty()
        rebuilt.version shouldBe 2
        shouldThrow<IllegalArgumentException> { RouteCode.of("north 1") }
        RouteCodeAlreadyInUse(RouteCode.of("NORTH1")).code shouldBe "locations.route-code-in-use"
        RouteStopAtUnavailableStore(listOf(storeA)).code shouldBe "locations.route-stop-store-unavailable"
    }

    @Test
    fun `a warehouse has a number, a name, and a time zone that can change`() {
        val warehouse =
            Warehouse.create(WarehouseNumber.of("900"), LocationName.of("Central DC"), ZoneId.of("UTC"), clock)

        warehouse.updateDetails(LocationName.of("Central distribution"), ZoneId.of("America/Chicago"))

        warehouse.name.value shouldBe "Central distribution"
        warehouse.timeZone shouldBe ZoneId.of("America/Chicago")
        warehouse.version shouldBe 0
        shouldThrow<IllegalArgumentException> { WarehouseNumber.of("9A") }
        WarehouseNumberAlreadyInUse(warehouse.number).code shouldBe "locations.warehouse-number-in-use"
        StoreStillOnRoute(StoreNumber.of("7")).code shouldBe "locations.store-still-on-route"
    }
}
