package com.retailplatform.locations.domain

import com.retailplatform.locations.api.events.RouteScheduleChanged
import com.retailplatform.locations.api.events.RouteStopFacts
import com.retailplatform.shared.AggregateRoot
import com.retailplatform.shared.BusinessDate
import com.retailplatform.shared.BusinessRuleViolation
import com.retailplatform.shared.EventId
import com.retailplatform.shared.RouteId
import com.retailplatform.shared.WarehouseId
import java.time.Clock

/** A short code for a route, such as `NORTH1`: 1 to 10 upper-case letters or digits. */
@JvmInline
value class RouteCode private constructor(
    val value: String,
) {
    override fun toString(): String = value

    companion object {
        private val FORMAT = Regex("[A-Z0-9]{1,10}")

        /** @throws IllegalArgumentException unless [value] is 1 to 10 upper-case letters or digits. */
        fun of(value: String): RouteCode {
            require(FORMAT.matches(value)) { "Not a route code: '$value'" }
            return RouteCode(value)
        }
    }
}

/** A truck run from one warehouse to a set of stores on set weekdays. */
class DeliveryRoute private constructor(
    val id: RouteId,
    val code: RouteCode,
    val name: LocationName,
    val warehouseId: WarehouseId,
    schedule: RouteSchedule,
    val version: Int,
) : AggregateRoot() {
    var schedule: RouteSchedule = schedule
        private set

    /** Replaces the schedule, raising [RouteScheduleChanged] unless it is unchanged. */
    fun changeSchedule(
        newSchedule: RouteSchedule,
        clock: Clock,
    ) {
        if (newSchedule == schedule) return
        schedule = newSchedule
        raiseScheduleChanged(clock)
    }

    private fun raiseScheduleChanged(clock: Clock) {
        raise(
            RouteScheduleChanged(
                eventId = EventId.generate(clock),
                occurredAt = clock.instant(),
                businessDate = BusinessDate.today(clock),
                routeId = id,
                routeCode = code.value,
                warehouseId = warehouseId,
                stops =
                    schedule.stops.mapIndexed { index, stop ->
                        RouteStopFacts(
                            storeId = stop.storeId,
                            deliveryDay = stop.deliveryDay,
                            sequence = index + 1,
                            cutoffDaysBefore = stop.cutoff.daysBefore,
                            cutoffTime = stop.cutoff.time,
                        )
                    },
            ),
        )
    }

    companion object {
        /** Creates a route with its first schedule, raising [RouteScheduleChanged]. */
        fun create(
            code: RouteCode,
            name: LocationName,
            warehouseId: WarehouseId,
            schedule: RouteSchedule,
            clock: Clock,
        ): DeliveryRoute =
            DeliveryRoute(RouteId.generate(clock), code, name, warehouseId, schedule, version = 0)
                .also { it.raiseScheduleChanged(clock) }

        /** Rebuilds a route from stored state. Raises no events. */
        fun rehydrate(
            id: RouteId,
            code: RouteCode,
            name: LocationName,
            warehouseId: WarehouseId,
            schedule: RouteSchedule,
            version: Int,
        ): DeliveryRoute = DeliveryRoute(id, code, name, warehouseId, schedule, version)
    }
}

class RouteCodeAlreadyInUse(
    code: RouteCode,
) : BusinessRuleViolation("locations.route-code-in-use", "Route code $code is already in use")

class RouteStopAtUnavailableStore(
    storeIds: Collection<Any>,
) : BusinessRuleViolation(
        "locations.route-stop-store-unavailable",
        "A route can only stop at open stores; not open or unknown: ${storeIds.joinToString()}",
    )
