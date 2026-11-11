package com.retailplatform.catalog.adapter.`in`.web

import com.retailplatform.catalog.application.ChangeItem
import com.retailplatform.catalog.application.ChangeItemCommand
import com.retailplatform.catalog.application.CreateItem
import com.retailplatform.catalog.application.CreateItemCommand
import com.retailplatform.catalog.application.DiscontinueItem
import com.retailplatform.catalog.application.DiscontinueItemCommand
import com.retailplatform.catalog.application.ItemDetailsInput
import com.retailplatform.catalog.application.ItemQueries
import com.retailplatform.catalog.application.SetVendorItem
import com.retailplatform.catalog.application.SetVendorItemCommand
import com.retailplatform.catalog.domain.CategoryCode
import com.retailplatform.catalog.domain.Item
import com.retailplatform.catalog.domain.ItemDescription
import com.retailplatform.catalog.domain.ItemNumber
import com.retailplatform.catalog.domain.ItemUnits
import com.retailplatform.catalog.domain.Pack
import com.retailplatform.catalog.domain.Upc
import com.retailplatform.catalog.domain.VendorItem
import com.retailplatform.platform.web.MoneyDto
import com.retailplatform.platform.web.PageResponse
import com.retailplatform.platform.web.mapRequest
import com.retailplatform.platform.web.pageQuery
import com.retailplatform.shared.BusinessDate
import com.retailplatform.shared.ItemId
import com.retailplatform.shared.UnitOfMeasure
import com.retailplatform.shared.VendorId
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Positive
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
import java.math.BigDecimal
import java.net.URI
import java.time.LocalDate

data class PackDto(
    @field:NotBlank
    val unit: String?,
    @field:NotNull @field:Positive
    val baseUnits: BigDecimal?,
) {
    fun toDomain(): Pack = Pack.of(UnitOfMeasure.of(requireNotNull(unit)), requireNotNull(baseUnits))
}

/** The parts of an item that can be entered and changed. */
data class ItemDetailsDto(
    @field:NotBlank @field:Size(max = ItemDescription.MAX_LENGTH)
    val description: String?,
    @field:NotBlank
    val categoryCode: String?,
    @field:NotBlank
    val baseUnit: String?,
    @field:Valid
    val packs: List<PackDto> = emptyList(),
    val upcs: List<
        @Pattern(regexp = "[0-9]{12,13}", message = "must be 12 or 13 digits")
        String,
    > = emptyList(),
) {
    fun toDomain(): ItemDetailsInput =
        ItemDetailsInput(
            description = ItemDescription.of(requireNotNull(description)),
            categoryCode = CategoryCode.of(requireNotNull(categoryCode)),
            units = ItemUnits.of(UnitOfMeasure.of(requireNotNull(baseUnit)), packs.map { it.toDomain() }),
            upcs = upcs.map(Upc::of).toSet(),
        )
}

data class CreateItemRequest(
    @field:NotBlank @field:Pattern(regexp = "[0-9]{1,10}", message = "must be one to ten digits")
    val number: String?,
    @field:NotNull @field:Valid
    val details: ItemDetailsDto?,
) {
    fun toCommand(): CreateItemCommand =
        mapRequest { CreateItemCommand(ItemNumber.of(requireNotNull(number)), requireNotNull(details).toDomain()) }
}

data class DiscontinueItemRequest(
    @field:NotNull
    val lastDay: LocalDate?,
)

data class VendorItemDto(
    @field:NotBlank @field:Size(max = VendorItem.MAX_SKU_LENGTH)
    val vendorSku: String?,
    @field:NotNull @field:Min(1)
    val casePack: Int?,
    @field:NotNull @field:Valid
    val listCost: MoneyDto?,
) {
    fun toDomain(vendorId: VendorId): VendorItem =
        VendorItem.of(
            vendorId,
            requireNotNull(vendorSku),
            requireNotNull(casePack),
            requireNotNull(listCost).toDomain(),
        )
}

data class ItemVendorResponse(
    val vendorId: String,
    val vendorSku: String,
    val casePack: Int,
    val listCost: MoneyDto,
)

data class ItemResponse(
    val id: String,
    val number: String,
    val description: String,
    val departmentCode: String,
    val categoryCode: String,
    val baseUnit: String,
    val packs: List<PackDto>,
    val upcs: List<String>,
    val vendors: List<ItemVendorResponse>,
    val discontinuedOn: LocalDate?,
) {
    companion object {
        fun from(item: Item): ItemResponse =
            with(item.details) {
                ItemResponse(
                    id = item.id.toString(),
                    number = item.number.value,
                    description = description.value,
                    departmentCode = category.departmentCode.value,
                    categoryCode = category.code.value,
                    baseUnit = units.baseUnit.code,
                    packs = units.packs.map { PackDto(it.unit.code, it.baseUnits) },
                    upcs = upcs.map { it.value }.sorted(),
                    vendors =
                        item.vendorItems.map {
                            ItemVendorResponse(
                                it.vendorId.toString(),
                                it.vendorSku,
                                it.casePack,
                                MoneyDto.from(it.listCost),
                            )
                        },
                    discontinuedOn = item.discontinuedOn?.value,
                )
            }
    }
}

@RestController
@RequestMapping("/api/v1/items")
class ItemController(
    private val createItem: CreateItem,
    private val changeItem: ChangeItem,
    private val discontinueItem: DiscontinueItem,
    private val setVendorItem: SetVendorItem,
    private val queries: ItemQueries,
) {
    @PostMapping
    fun create(
        @Valid @RequestBody request: CreateItemRequest,
    ): ResponseEntity<ItemResponse> {
        val id = createItem.handle(request.toCommand())
        return ResponseEntity.created(URI.create("/api/v1/items/$id")).body(ItemResponse.from(queries.find(id)))
    }

    @GetMapping
    fun list(
        @RequestParam(required = false) page: Int?,
        @RequestParam(required = false) size: Int?,
    ): PageResponse<ItemResponse> = PageResponse.from(queries.list(pageQuery(page, size)), ItemResponse::from)

    @GetMapping("/{id}")
    fun get(
        @PathVariable id: String,
    ): ItemResponse = ItemResponse.from(queries.find(itemId(id)))

    @PutMapping("/{id}")
    fun change(
        @PathVariable id: String,
        @Valid @RequestBody request: ItemDetailsDto,
    ): ItemResponse {
        val itemId = itemId(id)
        changeItem.handle(mapRequest { ChangeItemCommand(itemId, request.toDomain()) })
        return ItemResponse.from(queries.find(itemId))
    }

    @PostMapping("/{id}/discontinue")
    fun discontinue(
        @PathVariable id: String,
        @Valid @RequestBody request: DiscontinueItemRequest,
    ): ItemResponse {
        val itemId = itemId(id)
        discontinueItem.handle(DiscontinueItemCommand(itemId, BusinessDate(requireNotNull(request.lastDay))))
        return ItemResponse.from(queries.find(itemId))
    }

    @PutMapping("/{id}/vendors/{vendorId}")
    fun setVendorItem(
        @PathVariable id: String,
        @PathVariable vendorId: String,
        @Valid @RequestBody request: VendorItemDto,
    ): ItemResponse {
        val itemId = itemId(id)
        setVendorItem.handle(
            mapRequest { SetVendorItemCommand(itemId, request.toDomain(VendorId.parse(vendorId))) },
        )
        return ItemResponse.from(queries.find(itemId))
    }

    private fun itemId(id: String): ItemId = mapRequest { ItemId.parse(id) }
}
