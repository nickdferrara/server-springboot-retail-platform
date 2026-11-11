package com.retailplatform.pricing.adapter.`in`.web

import com.retailplatform.platform.web.MoneyDto
import com.retailplatform.platform.web.mapRequest
import com.retailplatform.pricing.application.AssignStoreToPriceZone
import com.retailplatform.pricing.application.AssignStoreToPriceZoneCommand
import com.retailplatform.pricing.application.CreatePriceZone
import com.retailplatform.pricing.application.PriceQueries
import com.retailplatform.pricing.application.PriceZoneQueries
import com.retailplatform.pricing.application.ScheduleRetailPrice
import com.retailplatform.pricing.application.ScheduleRetailPriceCommand
import com.retailplatform.pricing.domain.PriceScope
import com.retailplatform.pricing.domain.PriceZone
import com.retailplatform.pricing.domain.PriceZoneCode
import com.retailplatform.pricing.domain.RetailPrice
import com.retailplatform.shared.BusinessDate
import com.retailplatform.shared.ItemId
import com.retailplatform.shared.StoreId
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate

data class PriceZoneDto(
    @field:NotBlank
    @field:Pattern(
        regexp = "[A-Z][A-Z0-9_]{0,19}",
        message = "must be 1 to 20 upper-case letters, digits, or underscores",
    )
    val code: String?,
    @field:NotBlank @field:Size(max = PriceZone.MAX_NAME_LENGTH)
    val name: String?,
)

data class StorePriceZoneResponse(
    val storeId: String,
    val zoneCode: String,
)

data class ScheduleRetailPriceRequest(
    @field:NotBlank
    val itemId: String?,
    val zoneCode: String? = null,
    val storeId: String? = null,
    @field:NotNull @field:Valid
    val price: MoneyDto?,
    @field:NotNull
    val effectiveFrom: LocalDate?,
) {
    fun toCommand(): ScheduleRetailPriceCommand =
        mapRequest {
            require((zoneCode == null) != (storeId == null)) { "Give exactly one of zoneCode and storeId" }
            ScheduleRetailPriceCommand(
                itemId = ItemId.parse(requireNotNull(itemId)),
                scope =
                    zoneCode?.let { PriceScope.Zone(PriceZoneCode.of(it)) }
                        ?: PriceScope.Store(StoreId.parse(requireNotNull(storeId))),
                price = requireNotNull(price).toDomain(),
                effectiveFrom = BusinessDate(requireNotNull(effectiveFrom)),
            )
        }
}

data class RetailPriceResponse(
    val id: String,
    val itemId: String,
    val zoneCode: String?,
    val storeId: String?,
    val price: MoneyDto,
    val effectiveFrom: LocalDate,
    val announcedOn: LocalDate?,
) {
    companion object {
        fun from(price: RetailPrice): RetailPriceResponse =
            RetailPriceResponse(
                id = price.id.toString(),
                itemId = price.itemId.toString(),
                zoneCode = (price.scope as? PriceScope.Zone)?.code?.value,
                storeId = (price.scope as? PriceScope.Store)?.storeId?.toString(),
                price = MoneyDto.from(price.price),
                effectiveFrom = price.effectiveFrom.value,
                announcedOn = price.announcedOn?.value,
            )
    }
}

data class EffectivePriceResponse(
    val itemId: String,
    val storeId: String,
    val date: LocalDate,
    val price: MoneyDto,
    val source: RetailPriceResponse,
)

@RestController
@RequestMapping("/api/v1/price-zones")
class PriceZoneController(
    private val createPriceZone: CreatePriceZone,
    private val assignStoreToPriceZone: AssignStoreToPriceZone,
    private val queries: PriceZoneQueries,
) {
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @Valid @RequestBody request: PriceZoneDto,
    ): PriceZoneDto {
        val zone =
            mapRequest { PriceZone.of(PriceZoneCode.of(requireNotNull(request.code)), requireNotNull(request.name)) }
        createPriceZone.handle(zone)
        return PriceZoneDto(zone.code.value, zone.name)
    }

    @GetMapping
    fun list(): List<PriceZoneDto> = queries.zones().map { PriceZoneDto(it.code.value, it.name) }

    @PutMapping("/{code}/stores/{storeId}")
    fun assignStore(
        @PathVariable code: String,
        @PathVariable storeId: String,
    ): StorePriceZoneResponse {
        val command = mapRequest { AssignStoreToPriceZoneCommand(StoreId.parse(storeId), PriceZoneCode.of(code)) }
        assignStoreToPriceZone.handle(command)
        val assignment = queries.zoneOf(command.storeId)
        return StorePriceZoneResponse(assignment.storeId.toString(), assignment.zone.value)
    }
}

@RestController
@RequestMapping("/api/v1/retail-prices")
class RetailPriceController(
    private val scheduleRetailPrice: ScheduleRetailPrice,
    private val queries: PriceQueries,
) {
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun schedule(
        @Valid @RequestBody request: ScheduleRetailPriceRequest,
    ): RetailPriceResponse {
        val command = request.toCommand()
        val id = scheduleRetailPrice.handle(command)
        return RetailPriceResponse.from(queries.forItem(command.itemId).first { it.id == id })
    }

    @GetMapping
    fun forItem(
        @RequestParam itemId: String,
    ): List<RetailPriceResponse> = queries.forItem(mapRequest { ItemId.parse(itemId) }).map(RetailPriceResponse::from)

    @GetMapping("/effective")
    fun effective(
        @RequestParam itemId: String,
        @RequestParam storeId: String,
        @RequestParam date: LocalDate,
    ): EffectivePriceResponse {
        val resolved =
            queries.resolve(
                mapRequest { ItemId.parse(itemId) },
                mapRequest { StoreId.parse(storeId) },
                BusinessDate(date),
            )
        return EffectivePriceResponse(
            itemId = resolved.itemId.toString(),
            storeId = resolved.storeId.toString(),
            date = resolved.date.value,
            price = MoneyDto.from(resolved.source.price),
            source = RetailPriceResponse.from(resolved.source),
        )
    }
}
