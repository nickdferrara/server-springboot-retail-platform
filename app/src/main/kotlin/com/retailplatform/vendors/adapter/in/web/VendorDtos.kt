package com.retailplatform.vendors.adapter.`in`.web

import com.retailplatform.platform.web.mapRequest
import com.retailplatform.shared.Percentage
import com.retailplatform.shared.VendorId
import com.retailplatform.vendors.application.ChangeVendorTermsCommand
import com.retailplatform.vendors.application.CreateVendorCommand
import com.retailplatform.vendors.application.UpdateVendorDetailsCommand
import com.retailplatform.vendors.domain.PaymentTerms
import com.retailplatform.vendors.domain.PostalAddress
import com.retailplatform.vendors.domain.Vendor
import com.retailplatform.vendors.domain.VendorContact
import com.retailplatform.vendors.domain.VendorName
import com.retailplatform.vendors.domain.VendorNumber
import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.math.BigDecimal

data class PaymentTermsDto(
    @field:NotNull @field:Min(0)
    val netDays: Int?,
    val discountPercent: BigDecimal? = null,
    @field:Min(0)
    val discountDays: Int? = null,
) {
    fun toDomain(): PaymentTerms {
        val net = requireNotNull(netDays)
        require((discountPercent == null) == (discountDays == null)) {
            "discountPercent and discountDays must be given together"
        }
        return if (discountPercent == null || discountDays == null) {
            PaymentTerms.net(net)
        } else {
            PaymentTerms.withDiscount(Percentage.of(discountPercent), discountDays, net)
        }
    }

    companion object {
        fun from(terms: PaymentTerms) =
            PaymentTermsDto(terms.netDays, terms.discount?.percent?.value, terms.discount?.days)
    }
}

data class PostalAddressDto(
    @field:NotBlank @field:Size(max = 100)
    val line1: String?,
    @field:Size(max = 100)
    val line2: String? = null,
    @field:NotBlank @field:Size(max = 100)
    val city: String?,
    @field:NotBlank @field:Size(max = 100)
    val region: String?,
    @field:NotBlank @field:Size(max = 12)
    val postalCode: String?,
    @field:NotBlank @field:Pattern(regexp = "[A-Z]{2}", message = "must be a two-letter ISO country code")
    val countryCode: String?,
) {
    fun toDomain(): PostalAddress =
        PostalAddress.of(
            requireNotNull(line1),
            line2,
            requireNotNull(city),
            requireNotNull(region),
            requireNotNull(postalCode),
            requireNotNull(countryCode),
        )

    companion object {
        fun from(address: PostalAddress) =
            with(address) { PostalAddressDto(line1, line2, city, region, postalCode, countryCode) }
    }
}

data class VendorContactDto(
    @field:NotBlank @field:Size(max = 100)
    val name: String?,
    @field:Email @field:Size(max = 100)
    val email: String? = null,
    @field:Size(max = 100)
    val phone: String? = null,
) {
    fun toDomain(): VendorContact = VendorContact.of(requireNotNull(name), email, phone)

    companion object {
        fun from(contact: VendorContact) = VendorContactDto(contact.name, contact.email, contact.phone)
    }
}

data class CreateVendorRequest(
    @field:NotBlank @field:Pattern(regexp = "[0-9]{1,10}", message = "must be one to ten digits")
    val number: String?,
    @field:NotBlank @field:Size(max = VendorName.MAX_LENGTH)
    val name: String?,
    @field:NotNull @field:Valid
    val paymentTerms: PaymentTermsDto?,
    @field:NotNull @field:Valid
    val remitTo: PostalAddressDto?,
    @field:Valid
    val contacts: List<VendorContactDto> = emptyList(),
) {
    fun toCommand(): CreateVendorCommand =
        mapRequest {
            CreateVendorCommand(
                number = VendorNumber.of(requireNotNull(number)),
                name = VendorName.of(requireNotNull(name)),
                paymentTerms = requireNotNull(paymentTerms).toDomain(),
                remitTo = requireNotNull(remitTo).toDomain(),
                contacts = contacts.map { it.toDomain() },
            )
        }
}

data class UpdateVendorDetailsRequest(
    @field:NotBlank @field:Size(max = VendorName.MAX_LENGTH)
    val name: String?,
    @field:NotNull @field:Valid
    val remitTo: PostalAddressDto?,
    @field:Valid
    val contacts: List<VendorContactDto> = emptyList(),
) {
    fun toCommand(vendorId: VendorId): UpdateVendorDetailsCommand =
        mapRequest {
            UpdateVendorDetailsCommand(
                vendorId = vendorId,
                name = VendorName.of(requireNotNull(name)),
                remitTo = requireNotNull(remitTo).toDomain(),
                contacts = contacts.map { it.toDomain() },
            )
        }
}

fun PaymentTermsDto.toCommand(vendorId: VendorId): ChangeVendorTermsCommand =
    mapRequest { ChangeVendorTermsCommand(vendorId, toDomain()) }

data class VendorResponse(
    val id: String,
    val number: String,
    val name: String,
    val paymentTerms: PaymentTermsDto,
    val remitTo: PostalAddressDto,
    val contacts: List<VendorContactDto>,
) {
    companion object {
        fun from(vendor: Vendor): VendorResponse =
            VendorResponse(
                id = vendor.id.toString(),
                number = vendor.number.value,
                name = vendor.name.value,
                paymentTerms = PaymentTermsDto.from(vendor.paymentTerms),
                remitTo = PostalAddressDto.from(vendor.remitTo),
                contacts = vendor.contacts.map(VendorContactDto::from),
            )
    }
}
