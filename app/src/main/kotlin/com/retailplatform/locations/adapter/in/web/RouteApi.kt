package com.retailplatform.locations.adapter.`in`.web

import com.retailplatform.locations.application.ChangeRouteSchedule
import com.retailplatform.locations.application.ChangeRouteScheduleCommand
import com.retailplatform.locations.application.CreateRoute
import com.retailplatform.locations.application.CreateRouteCommand
import com.retailplatform.locations.application.RouteQueries
import com.retailplatform.locations.domain.DeliveryRoute
import com.retailplatform.locations.domain.LocationName
import com.retailplatform.locations.domain.OrderCutoff
import com.retailplatform.locations.domain.RouteCode
import com.retailplatform.locations.domain.RouteSchedule
import com.retailplatform.locations.domain.RouteStop
import com.retailplatform.platform.web.PageResponse
import com.retailplatform.platform.web.mapRequest
import com.retailplatform.platform.web.pageQuery
import com.retailplatform.shared.RouteId
import com.retailplatform.shared.StoreId
import com.retailplatform.shared.WarehouseId
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.time.DayOfWeek
import java.time.LocalTime

data class RouteStopDto(
    @field:NotBlank
    val storeId: String?,
    @field:NotNull
    val deliveryDay: DayOfWeek?,
    @field:NotNull @field:Min(0) @field:Max(OrderCutoff.MAX_DAYS_BEFORE.toLong())
    val cutoffDaysBefore: Int?,
    @field:NotNull
    val cutoffTime: LocalTime?,
) {
    fun toDomain(): RouteStop =
        RouteStop(
            storeId = StoreId.parse(requireNotNull(storeId)),
            deliveryDay = requireNotNull(deliveryDay),
            cutoff = OrderCutoff.of(requireNotNull(cutoffDaysBefore), requireNotNull(cutoffTime)),
        )

    companion object {
        fun from(stop: RouteStop) =
            RouteStopDto(stop.storeId.toString(), stop.deliveryDay, stop.cutoff.daysBefore, stop.cutoff.time)
    }
}

data class RouteScheduleRequest(
    @field:Valid
    val stops: List<RouteStopDto> = emptyList(),
) {
    fun toDomain(): RouteSchedule = RouteSchedule.of(stops.map { it.toDomain() })
}

data class CreateRouteRequest(
    @field:NotBlank
    @field:Pattern(regexp = "[A-Z0-9]{1,10}", message = "must be 1 to 10 upper-case letters or digits")
    val code: String?,
    @field:NotBlank
    @field:Size(max = LocationName.MAX_LENGTH)
    val name: String?,
    @field:NotBlank
    val warehouseId: String?,
    @field:Valid
    val stops: List<RouteStopDto> = emptyList(),
) {
    fun toCommand(): CreateRouteCommand =
        mapRequest {
            CreateRouteCommand(
                code = RouteCode.of(requireNotNull(code)),
                name = LocationName.of(requireNotNull(name)),
                warehouseId = WarehouseId.parse(requireNotNull(warehouseId)),
                schedule = RouteSchedule.of(stops.map { it.toDomain() }),
            )
        }
}

data class RouteResponse(
    val id: String,
    val code: String,
    val name: String,
    val warehouseId: String,
    val stops: List<RouteStopDto>,
) {
    companion object {
        fun from(route: DeliveryRoute): RouteResponse =
            RouteResponse(
                id = route.id.toString(),
                code = route.code.value,
                name = route.name.value,
                warehouseId = route.warehouseId.toString(),
                stops = route.schedule.stops.map(RouteStopDto::from),
            )
    }
}

@RestController
@RequestMapping("/api/v1/routes")
class RouteController(
    private val createRoute: CreateRoute,
    private val changeRouteSchedule: ChangeRouteSchedule,
    private val queries: RouteQueries,
) {
    @PostMapping
    fun create(
        @Valid @RequestBody request: CreateRouteRequest,
    ): ResponseEntity<RouteResponse> {
        val id = createRoute.handle(request.toCommand())
        return ResponseEntity.created(URI.create("/api/v1/routes/$id")).body(RouteResponse.from(queries.find(id)))
    }

    @GetMapping
    fun list(
        @RequestParam(required = false) page: Int?,
        @RequestParam(required = false) size: Int?,
    ): PageResponse<RouteResponse> = PageResponse.from(queries.list(pageQuery(page, size)), RouteResponse::from)

    @GetMapping("/{id}")
    fun get(
        @PathVariable id: String,
    ): RouteResponse = RouteResponse.from(queries.find(routeId(id)))

    @PutMapping("/{id}/schedule")
    fun changeSchedule(
        @PathVariable id: String,
        @Valid @RequestBody request: RouteScheduleRequest,
    ): RouteResponse {
        val routeId = routeId(id)
        changeRouteSchedule.handle(mapRequest { ChangeRouteScheduleCommand(routeId, request.toDomain()) })
        return RouteResponse.from(queries.find(routeId))
    }

    private fun routeId(id: String): RouteId = mapRequest { RouteId.parse(id) }
}
