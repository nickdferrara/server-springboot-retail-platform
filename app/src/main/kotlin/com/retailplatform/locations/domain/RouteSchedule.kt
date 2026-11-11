package com.retailplatform.locations.domain

import com.retailplatform.shared.StoreId
import java.time.DayOfWeek
import java.time.LocalTime

/** When a store's order locks for a delivery: at [time], store time, [daysBefore] days before the delivery day. */
@ConsistentCopyVisibility
data class OrderCutoff private constructor(
    val daysBefore: Int,
    val time: LocalTime,
) {
    companion object {
        const val MAX_DAYS_BEFORE = 6

        /** @throws IllegalArgumentException unless [daysBefore] is between 0 and [MAX_DAYS_BEFORE]. */
        fun of(
            daysBefore: Int,
            time: LocalTime,
        ): OrderCutoff {
            require(daysBefore in 0..MAX_DAYS_BEFORE) {
                "An order cutoff is 0 to $MAX_DAYS_BEFORE days before delivery: $daysBefore"
            }
            return OrderCutoff(daysBefore, time)
        }
    }
}

/** One delivery on a route: a store, the weekday the truck arrives, and when that store's order locks. */
data class RouteStop(
    val storeId: StoreId,
    val deliveryDay: DayOfWeek,
    val cutoff: OrderCutoff,
)

/**
 * The stops of a route, in the order the truck makes them. A store may be delivered on several weekdays, but only
 * once on any one weekday.
 */
@ConsistentCopyVisibility
data class RouteSchedule private constructor(
    val stops: List<RouteStop>,
) {
    val storeIds: Set<StoreId> get() = stops.map { it.storeId }.toSet()

    companion object {
        val EMPTY = RouteSchedule(emptyList())

        /** @throws IllegalArgumentException if a store appears twice on the same weekday. */
        fun of(stops: List<RouteStop>): RouteSchedule {
            val repeated = stops.groupBy { it.storeId to it.deliveryDay }.filterValues { it.size > 1 }.keys
            require(repeated.isEmpty()) {
                "A store can be delivered once per weekday on a route; repeated: " +
                    repeated.joinToString { (store, day) -> "$store on $day" }
            }
            return RouteSchedule(stops.toList())
        }
    }
}
