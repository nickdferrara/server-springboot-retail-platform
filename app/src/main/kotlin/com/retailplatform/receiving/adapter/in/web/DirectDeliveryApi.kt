package com.retailplatform.receiving.adapter.`in`.web

import com.retailplatform.platform.web.MoneyDto
import com.retailplatform.platform.web.PageResponse
import com.retailplatform.platform.web.mapRequest
import com.retailplatform.platform.web.pageQuery
import com.retailplatform.receiving.application.DirectDeliveryQueries
import com.retailplatform.receiving.application.ReceiveDirectDelivery
import com.retailplatform.receiving.application.ReceiveDirectDeliveryCommand
import com.retailplatform.receiving.application.SetAgreedCost
import com.retailplatform.receiving.application.SetAgreedCostCommand
import com.retailplatform.receiving.domain.AgreedCost
import com.retailplatform.receiving.domain.DeliveryTicket
import com.retailplatform.receiving.domain.DeliveryTicketNumber
import com.retailplatform.receiving.domain.DirectDelivery
import com.retailplatform.receiving.domain.TicketLine
import com.retailplatform.shared.BusinessDate
import com.retailplatform.shared.DirectDeliveryId
import com.retailplatform.shared.ItemId
import com.retailplatform.shared.StoreId
import com.retailplatform.shared.VendorId
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull
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
import java.time.LocalDate

data class SetAgreedCostRequest(
    @field:NotBlank
    val vendorId: String?,
    @field:NotBlank
    val itemId: String?,
    @field:NotEmpty
    val storeIds: List<String>?,
    @field:NotNull @field:Valid
    val unitCost: MoneyDto?,
    @field:NotNull
    val effectiveFrom: LocalDate?,
) {
    fun toCommand(): SetAgreedCostCommand =
        mapRequest {
            val cost = requireNotNull(unitCost).toDomain()
            require(!cost.isNegative) { "An agreed cost cannot be negative" }
            SetAgreedCostCommand(
                vendorId = VendorId.parse(requireNotNull(vendorId)),
                itemId = ItemId.parse(requireNotNull(itemId)),
                storeIds = requireNotNull(storeIds).map(StoreId::parse),
                unitCost = cost,
                effectiveFrom = BusinessDate(requireNotNull(effectiveFrom)),
            )
        }
}

data class AgreedCostResponse(
    val vendorId: String,
    val itemId: String,
    val storeId: String,
    val unitCost: MoneyDto,
    val effectiveFrom: LocalDate,
) {
    companion object {
        fun from(cost: AgreedCost) =
            AgreedCostResponse(
                vendorId = cost.vendorId.toString(),
                itemId = cost.itemId.toString(),
                storeId = cost.storeId.toString(),
                unitCost = MoneyDto.from(cost.unitCost),
                effectiveFrom = cost.effectiveFrom.value,
            )
    }
}

data class TicketLineRequest(
    @field:NotBlank
    val itemId: String?,
    @field:Min(0)
    val unitsDelivered: Int = 0,
    @field:Min(0)
    val unitsReturned: Int = 0,
)

data class ReceiveDirectDeliveryRequest(
    @field:NotBlank
    val vendorId: String?,
    @field:NotBlank
    val storeId: String?,
    @field:NotBlank @field:Size(max = DeliveryTicketNumber.MAX_LENGTH)
    val ticketNumber: String?,
    @field:NotEmpty @field:Valid
    val lines: List<TicketLineRequest>?,
) {
    fun toCommand(): ReceiveDirectDeliveryCommand =
        mapRequest {
            ReceiveDirectDeliveryCommand(
                ticket =
                    DeliveryTicket(
                        VendorId.parse(requireNotNull(vendorId)),
                        StoreId.parse(requireNotNull(storeId)),
                        DeliveryTicketNumber.of(requireNotNull(ticketNumber)),
                    ),
                counted =
                    requireNotNull(lines).map {
                        TicketLine.of(ItemId.parse(requireNotNull(it.itemId)), it.unitsDelivered, it.unitsReturned)
                    },
            )
        }
}

data class DeliveredLineResponse(
    val lineNumber: Int,
    val itemId: String,
    val unitsDelivered: Int,
    val unitsReturned: Int,
    val unitCost: MoneyDto,
    val deliveredCost: MoneyDto,
    val returnedCost: MoneyDto,
)

data class DirectDeliveryResponse(
    val id: String,
    val receiptNumber: String,
    val vendorId: String,
    val storeId: String,
    val ticketNumber: String,
    val receivedOn: LocalDate,
    val receivedBy: String,
    val deliveredCost: MoneyDto,
    val returnedCost: MoneyDto,
    val lines: List<DeliveredLineResponse>,
) {
    companion object {
        fun from(delivery: DirectDelivery) =
            DirectDeliveryResponse(
                id = delivery.id.toString(),
                receiptNumber = delivery.receiptNumber.value,
                vendorId = delivery.ticket.vendorId.toString(),
                storeId = delivery.ticket.storeId.toString(),
                ticketNumber = delivery.ticket.number.value,
                receivedOn = delivery.receivedOn.value,
                receivedBy = delivery.receivedBy,
                deliveredCost = MoneyDto.from(delivery.deliveredCost),
                returnedCost = MoneyDto.from(delivery.returnedCost),
                lines =
                    delivery.lines.map {
                        DeliveredLineResponse(
                            lineNumber = it.lineNumber,
                            itemId = it.itemId.toString(),
                            unitsDelivered = it.unitsDelivered,
                            unitsReturned = it.unitsReturned,
                            unitCost = MoneyDto.from(it.unitCost),
                            deliveredCost = MoneyDto.from(it.deliveredCost),
                            returnedCost = MoneyDto.from(it.returnedCost),
                        )
                    },
            )
    }
}

@RestController
@RequestMapping("/api/v1/direct-deliveries")
class DirectDeliveryController(
    private val receiveDirectDelivery: ReceiveDirectDelivery,
    private val setAgreedCost: SetAgreedCost,
    private val queries: DirectDeliveryQueries,
) {
    /** Checks in a vendor's delivery at a store from the driver's ticket, in the items' base units. */
    @PostMapping
    fun receive(
        @Valid @RequestBody request: ReceiveDirectDeliveryRequest,
    ): ResponseEntity<DirectDeliveryResponse> {
        val id = receiveDirectDelivery.handle(request.toCommand())
        return ResponseEntity
            .created(URI.create("/api/v1/direct-deliveries/$id"))
            .body(DirectDeliveryResponse.from(queries.delivery(id)))
    }

    /** A store's deliveries with the newest first. */
    @GetMapping
    fun forStore(
        @RequestParam storeId: String,
        @RequestParam(required = false) page: Int?,
        @RequestParam(required = false) size: Int?,
    ): PageResponse<DirectDeliveryResponse> {
        val store = mapRequest { StoreId.parse(storeId) }
        return PageResponse.from(queries.deliveriesTo(store, pageQuery(page, size)), DirectDeliveryResponse::from)
    }

    @GetMapping("/{id}")
    fun get(
        @PathVariable id: String,
    ): DirectDeliveryResponse = DirectDeliveryResponse.from(queries.delivery(mapRequest { DirectDeliveryId.parse(id) }))

    /** Sets what a vendor charges for one base unit of an item at each of the given stores, from a date on. */
    @PutMapping("/costs")
    fun setCost(
        @Valid @RequestBody request: SetAgreedCostRequest,
    ): ResponseEntity<Unit> {
        setAgreedCost.handle(request.toCommand())
        return ResponseEntity.noContent().build()
    }

    /** What a vendor charges a store for each item it may deliver there, on `asOf` or today. */
    @GetMapping("/costs")
    fun costs(
        @RequestParam vendorId: String,
        @RequestParam storeId: String,
        @RequestParam(required = false) asOf: LocalDate?,
    ): List<AgreedCostResponse> {
        val vendor = mapRequest { VendorId.parse(vendorId) }
        val store = mapRequest { StoreId.parse(storeId) }
        return queries.agreedCosts(vendor, store, asOf?.let(::BusinessDate)).map(AgreedCostResponse::from)
    }
}
