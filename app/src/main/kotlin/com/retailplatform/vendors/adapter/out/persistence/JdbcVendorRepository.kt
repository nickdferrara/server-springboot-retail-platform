package com.retailplatform.vendors.adapter.out.persistence

import com.retailplatform.platform.persistence.AuditMetadata
import com.retailplatform.shared.Page
import com.retailplatform.shared.PageQuery
import com.retailplatform.shared.Percentage
import com.retailplatform.shared.VendorId
import com.retailplatform.vendors.application.VendorRepository
import com.retailplatform.vendors.domain.PaymentTerms
import com.retailplatform.vendors.domain.PostalAddress
import com.retailplatform.vendors.domain.Vendor
import com.retailplatform.vendors.domain.VendorContact
import com.retailplatform.vendors.domain.VendorName
import com.retailplatform.vendors.domain.VendorNumber
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Repository
import kotlin.jvm.optionals.getOrNull

@Repository
class JdbcVendorRepository(
    private val vendors: VendorJdbcRepository,
) : VendorRepository {
    override fun save(vendor: Vendor) {
        // An update keeps the stored creation stamp; the version check refuses a stale write.
        val audit = if (vendor.version == 0) AuditMetadata() else vendors.findById(vendor.id.value).orElseThrow().audit
        vendors.save(vendor.toEntity(audit))
    }

    override fun findById(id: VendorId): Vendor? = vendors.findById(id.value).getOrNull()?.toDomain()

    override fun existsById(id: VendorId): Boolean = vendors.existsById(id.value)

    override fun existsByNumber(number: VendorNumber): Boolean = vendors.existsByNumber(number.value)

    override fun findPage(query: PageQuery): Page<Vendor> {
        val page = vendors.findAll(PageRequest.of(query.page, query.size, Sort.by("number")))
        return Page(page.content.map { it.toDomain() }, query.page, query.size, page.totalElements)
    }

    private fun Vendor.toEntity(audit: AuditMetadata) =
        VendorEntity(
            id = id.value,
            number = number.value,
            name = name.value,
            netDays = paymentTerms.netDays,
            discountPercent = paymentTerms.discount?.percent?.value,
            discountDays = paymentTerms.discount?.days,
            remitTo = with(remitTo) { AddressColumns(line1, line2, city, region, postalCode, countryCode) },
            contacts = contacts.map { VendorContactEntity(it.name, it.email, it.phone) },
            version = version,
            audit = audit,
        )

    private fun VendorEntity.toDomain(): Vendor =
        Vendor.rehydrate(
            id = VendorId.of(id),
            number = VendorNumber.of(number),
            name = VendorName.of(name),
            paymentTerms = paymentTerms(),
            remitTo = with(remitTo) { PostalAddress.of(line1, line2, city, region, postalCode, countryCode) },
            contacts = contacts.map { VendorContact.of(it.name, it.email, it.phone) },
            version = version,
        )

    private fun VendorEntity.paymentTerms(): PaymentTerms =
        if (discountPercent != null && discountDays != null) {
            PaymentTerms.withDiscount(Percentage.of(discountPercent), discountDays, netDays)
        } else {
            PaymentTerms.net(netDays)
        }
}
