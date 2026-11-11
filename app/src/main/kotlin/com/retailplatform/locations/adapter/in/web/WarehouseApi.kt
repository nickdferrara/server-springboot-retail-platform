package com.retailplatform.locations.adapter.`in`.web

import com.retailplatform.locations.application.CreateWarehouse
import com.retailplatform.locations.application.CreateWarehouseCommand
import com.retailplatform.locations.application.UpdateWarehouseDetails
import com.retailplatform.locations.application.UpdateWarehouseDetailsCommand
import com.retailplatform.locations.application.WarehouseQueries
import com.retailplatform.locations.domain.LocationName
import com.retailplatform.locations.domain.Warehouse
import com.retailplatform.locations.domain.WarehouseNumber
import com.retailplatform.platform.web.PageResponse
import com.retailplatform.platform.web.mapRequest
import com.retailplatform.platform.web.pageQuery
import com.retailplatform.shared.WarehouseId
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
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

data class CreateWarehouseRequest(
    @field:NotBlank
    @field:Pattern(regexp = "[0-9]{1,6}", message = "must be one to six digits")
    val number: String?,
    @field:NotBlank
    @field:Size(max = LocationName.MAX_LENGTH)
    val name: String?,
    @field:NotBlank
    val timeZone: String?,
) {
    fun toCommand(): CreateWarehouseCommand =
        mapRequest {
            CreateWarehouseCommand(
                number = WarehouseNumber.of(requireNotNull(number)),
                name = LocationName.of(requireNotNull(name)),
                timeZone = timeZoneOf(requireNotNull(timeZone)),
            )
        }
}

data class WarehouseResponse(
    val id: String,
    val number: String,
    val name: String,
    val timeZone: String,
) {
    companion object {
        fun from(warehouse: Warehouse): WarehouseResponse =
            WarehouseResponse(
                warehouse.id.toString(),
                warehouse.number.value,
                warehouse.name.value,
                warehouse.timeZone.id,
            )
    }
}

@RestController
@RequestMapping("/api/v1/warehouses")
class WarehouseController(
    private val createWarehouse: CreateWarehouse,
    private val updateWarehouseDetails: UpdateWarehouseDetails,
    private val queries: WarehouseQueries,
) {
    @PostMapping
    fun create(
        @Valid @RequestBody request: CreateWarehouseRequest,
    ): ResponseEntity<WarehouseResponse> {
        val id = createWarehouse.handle(request.toCommand())
        return ResponseEntity
            .created(URI.create("/api/v1/warehouses/$id"))
            .body(WarehouseResponse.from(queries.find(id)))
    }

    @GetMapping
    fun list(
        @RequestParam(required = false) page: Int?,
        @RequestParam(required = false) size: Int?,
    ): PageResponse<WarehouseResponse> = PageResponse.from(queries.list(pageQuery(page, size)), WarehouseResponse::from)

    @GetMapping("/{id}")
    fun get(
        @PathVariable id: String,
    ): WarehouseResponse = WarehouseResponse.from(queries.find(warehouseId(id)))

    @PutMapping("/{id}")
    fun updateDetails(
        @PathVariable id: String,
        @Valid @RequestBody request: UpdateLocationDetailsRequest,
    ): WarehouseResponse {
        val warehouseId = warehouseId(id)
        updateWarehouseDetails.handle(
            mapRequest {
                UpdateWarehouseDetailsCommand(
                    warehouseId,
                    LocationName.of(requireNotNull(request.name)),
                    timeZoneOf(requireNotNull(request.timeZone)),
                )
            },
        )
        return WarehouseResponse.from(queries.find(warehouseId))
    }

    private fun warehouseId(id: String): WarehouseId = mapRequest { WarehouseId.parse(id) }
}
