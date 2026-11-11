package com.retailplatform.vendors.adapter.`in`.web

import com.retailplatform.platform.web.PageResponse
import com.retailplatform.platform.web.mapRequest
import com.retailplatform.platform.web.pageQuery
import com.retailplatform.shared.VendorId
import com.retailplatform.vendors.application.ChangeVendorTerms
import com.retailplatform.vendors.application.CreateVendor
import com.retailplatform.vendors.application.UpdateVendorDetails
import com.retailplatform.vendors.application.VendorQueries
import jakarta.validation.Valid
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

@RestController
@RequestMapping("/api/v1/vendors")
class VendorController(
    private val createVendor: CreateVendor,
    private val updateVendorDetails: UpdateVendorDetails,
    private val changeVendorTerms: ChangeVendorTerms,
    private val queries: VendorQueries,
) {
    @PostMapping
    fun create(
        @Valid @RequestBody request: CreateVendorRequest,
    ): ResponseEntity<VendorResponse> {
        val id = createVendor.handle(request.toCommand())
        return ResponseEntity.created(URI.create("/api/v1/vendors/$id")).body(VendorResponse.from(queries.find(id)))
    }

    @GetMapping
    fun list(
        @RequestParam(required = false) page: Int?,
        @RequestParam(required = false) size: Int?,
    ): PageResponse<VendorResponse> = PageResponse.from(queries.list(pageQuery(page, size)), VendorResponse::from)

    @GetMapping("/{id}")
    fun get(
        @PathVariable id: String,
    ): VendorResponse = VendorResponse.from(queries.find(vendorId(id)))

    @PutMapping("/{id}")
    fun updateDetails(
        @PathVariable id: String,
        @Valid @RequestBody request: UpdateVendorDetailsRequest,
    ): VendorResponse {
        val vendorId = vendorId(id)
        updateVendorDetails.handle(request.toCommand(vendorId))
        return VendorResponse.from(queries.find(vendorId))
    }

    @PutMapping("/{id}/payment-terms")
    fun changePaymentTerms(
        @PathVariable id: String,
        @Valid @RequestBody request: PaymentTermsDto,
    ): VendorResponse {
        val vendorId = vendorId(id)
        changeVendorTerms.handle(request.toCommand(vendorId))
        return VendorResponse.from(queries.find(vendorId))
    }

    private fun vendorId(id: String): VendorId = mapRequest { VendorId.parse(id) }
}
