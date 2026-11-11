package com.retailplatform.locations.api.events

import com.retailplatform.shared.BusinessDate
import com.retailplatform.shared.EventId
import com.retailplatform.shared.RouteId
import com.retailplatform.shared.StoreId
import com.retailplatform.shared.WarehouseId
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime

/**
 * One delivery on a route: the truck reaches [storeId] on [deliveryDay], [sequence] in its run. The store's order
 * locks at [cutoffTime], store time, [cutoffDaysBefore] days before the delivery day.
 */
data class RouteStopFacts(
    val storeId: StoreId,
    val deliveryDay: DayOfWeek,
    val sequence: Int,
    val cutoffDaysBefore: Int,
    val cutoffTime: LocalTime,
)

/** A route's schedule was set or replaced. [stops] is the complete new schedule. */
data class RouteScheduleChanged(
    val eventId: EventId,
    val occurredAt: Instant,
    val businessDate: BusinessDate,
    val routeId: RouteId,
    val routeCode: String,
    val warehouseId: WarehouseId,
    val stops: List<RouteStopFacts>,
    val schemaVersion: Int = 1,
)
