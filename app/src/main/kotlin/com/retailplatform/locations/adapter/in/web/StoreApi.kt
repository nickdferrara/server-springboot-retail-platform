package com.retailplatform.locations.adapter.`in`.web

import com.retailplatform.locations.application.CloseStore
import com.retailplatform.locations.application.CloseStoreCommand
import com.retailplatform.locations.application.OpenStore
import com.retailplatform.locations.application.OpenStoreCommand
import com.retailplatform.locations.application.StoreQueries
import com.retailplatform.locations.application.UpdateStoreDetails
import com.retailplatform.locations.application.UpdateStoreDetailsCommand
import com.retailplatform.locations.domain.LocationName
import com.retailplatform.locations.domain.Store
import com.retailplatform.locations.domain.StoreNumber
import com.retailplatform.platform.web.PageResponse
import com.retailplatform.platform.web.mapRequest
import com.retailplatform.platform.web.pageQuery
import com.retailplatform.shared.BusinessDate
import com.retailplatform.shared.StoreId
import jakarta.validation.Valid
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
import java.time.DateTimeException
import java.time.LocalDate
import java.time.ZoneId

/** @throws IllegalArgumentException unless [id] is a time zone such as `America/Chicago`. */
internal fun timeZoneOf(id: String): ZoneId =
    try {
        ZoneId.of(id)
    } catch (e: DateTimeException) {
        throw IllegalArgumentException("Not a time zone: '$id'", e)
    }

data class OpenStoreRequest(
    @field:NotBlank
    @field:Pattern(regexp = "[0-9]{1,6}", message = "must be one to six digits")
    val number: String?,
    @field:NotBlank
    @field:Size(max = LocationName.MAX_LENGTH)
    val name: String?,
    @field:NotBlank
    val timeZone: String?,
    @field:NotNull
    val openedOn: LocalDate?,
) {
    fun toCommand(): OpenStoreCommand =
        mapRequest {
            OpenStoreCommand(
                number = StoreNumber.of(requireNotNull(number)),
                name = LocationName.of(requireNotNull(name)),
                timeZone = timeZoneOf(requireNotNull(timeZone)),
                openedOn = BusinessDate(requireNotNull(openedOn)),
            )
        }
}

data class UpdateLocationDetailsRequest(
    @field:NotBlank
    @field:Size(max = LocationName.MAX_LENGTH)
    val name: String?,
    @field:NotBlank
    val timeZone: String?,
)

data class CloseStoreRequest(
    @field:NotNull
    val lastTradingDay: LocalDate?,
)

data class StoreResponse(
    val id: String,
    val number: String,
    val name: String,
    val timeZone: String,
    val openedOn: LocalDate,
    val closedOn: LocalDate?,
    val open: Boolean,
) {
    companion object {
        fun from(store: Store): StoreResponse =
            StoreResponse(
                id = store.id.toString(),
                number = store.number.value,
                name = store.name.value,
                timeZone = store.timeZone.id,
                openedOn = store.openedOn.value,
                closedOn = store.closedOn?.value,
                open = store.isOpen,
            )
    }
}

@RestController
@RequestMapping("/api/v1/stores")
class StoreController(
    private val openStore: OpenStore,
    private val updateStoreDetails: UpdateStoreDetails,
    private val closeStore: CloseStore,
    private val queries: StoreQueries,
) {
    @PostMapping
    fun open(
        @Valid @RequestBody request: OpenStoreRequest,
    ): ResponseEntity<StoreResponse> {
        val id = openStore.handle(request.toCommand())
        return ResponseEntity.created(URI.create("/api/v1/stores/$id")).body(StoreResponse.from(queries.find(id)))
    }

    @GetMapping
    fun list(
        @RequestParam(required = false) page: Int?,
        @RequestParam(required = false) size: Int?,
    ): PageResponse<StoreResponse> = PageResponse.from(queries.list(pageQuery(page, size)), StoreResponse::from)

    @GetMapping("/{id}")
    fun get(
        @PathVariable id: String,
    ): StoreResponse = StoreResponse.from(queries.find(storeId(id)))

    @PutMapping("/{id}")
    fun updateDetails(
        @PathVariable id: String,
        @Valid @RequestBody request: UpdateLocationDetailsRequest,
    ): StoreResponse {
        val storeId = storeId(id)
        updateStoreDetails.handle(
            mapRequest {
                UpdateStoreDetailsCommand(
                    storeId,
                    LocationName.of(requireNotNull(request.name)),
                    timeZoneOf(requireNotNull(request.timeZone)),
                )
            },
        )
        return StoreResponse.from(queries.find(storeId))
    }

    @PostMapping("/{id}/close")
    fun close(
        @PathVariable id: String,
        @Valid @RequestBody request: CloseStoreRequest,
    ): StoreResponse {
        val storeId = storeId(id)
        closeStore.handle(CloseStoreCommand(storeId, BusinessDate(requireNotNull(request.lastTradingDay))))
        return StoreResponse.from(queries.find(storeId))
    }

    private fun storeId(id: String): StoreId = mapRequest { StoreId.parse(id) }
}
