package com.retailplatform.vendors.adapter.out.persistence

import com.retailplatform.platform.persistence.AuditMetadata
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.Version
import org.springframework.data.relational.core.mapping.Embedded
import org.springframework.data.relational.core.mapping.MappedCollection
import org.springframework.data.relational.core.mapping.Table
import org.springframework.data.repository.CrudRepository
import org.springframework.data.repository.PagingAndSortingRepository
import java.math.BigDecimal
import java.util.UUID

@Table(schema = "vendors", name = "vendor")
data class VendorEntity(
    @Id val id: UUID,
    val number: String,
    val name: String,
    val netDays: Int,
    val discountPercent: BigDecimal?,
    val discountDays: Int?,
    @Embedded.Empty(prefix = "remit_to_") val remitTo: AddressColumns,
    @MappedCollection(idColumn = "vendor_id", keyColumn = "position") val contacts: List<VendorContactEntity>,
    @Version val version: Int = 0,
    @Embedded.Empty val audit: AuditMetadata = AuditMetadata(),
)

data class AddressColumns(
    val line1: String,
    val line2: String?,
    val city: String,
    val region: String,
    val postalCode: String,
    val countryCode: String,
)

@Table(schema = "vendors", name = "vendor_contact")
data class VendorContactEntity(
    val name: String,
    val email: String?,
    val phone: String?,
)

interface VendorJdbcRepository :
    CrudRepository<VendorEntity, UUID>,
    PagingAndSortingRepository<VendorEntity, UUID> {
    fun existsByNumber(number: String): Boolean
}
