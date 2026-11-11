package com.retailplatform.catalog.adapter.out.persistence

import com.retailplatform.catalog.application.ItemRepository
import com.retailplatform.catalog.domain.CategoryCode
import com.retailplatform.catalog.domain.DepartmentCode
import com.retailplatform.catalog.domain.Item
import com.retailplatform.catalog.domain.ItemCategory
import com.retailplatform.catalog.domain.ItemDescription
import com.retailplatform.catalog.domain.ItemDetails
import com.retailplatform.catalog.domain.ItemNumber
import com.retailplatform.catalog.domain.ItemUnits
import com.retailplatform.catalog.domain.Pack
import com.retailplatform.catalog.domain.Upc
import com.retailplatform.catalog.domain.VendorItem
import com.retailplatform.platform.persistence.AuditMetadata
import com.retailplatform.shared.BusinessDate
import com.retailplatform.shared.ItemId
import com.retailplatform.shared.Money
import com.retailplatform.shared.Page
import com.retailplatform.shared.PageQuery
import com.retailplatform.shared.UnitOfMeasure
import com.retailplatform.shared.VendorId
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.Version
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.data.relational.core.mapping.Embedded
import org.springframework.data.relational.core.mapping.MappedCollection
import org.springframework.data.relational.core.mapping.Table
import org.springframework.data.repository.CrudRepository
import org.springframework.data.repository.PagingAndSortingRepository
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Currency
import java.util.UUID
import kotlin.jvm.optionals.getOrNull

@Table(schema = "catalog", name = "item")
data class ItemEntity(
    @Id val id: UUID,
    val number: String,
    val description: String,
    val categoryCode: String,
    val departmentCode: String,
    val baseUnit: String,
    val discontinuedOn: LocalDate?,
    @MappedCollection(idColumn = "item_id") val upcs: Set<ItemUpcEntity>,
    @MappedCollection(idColumn = "item_id", keyColumn = "position") val packs: List<ItemPackEntity>,
    @MappedCollection(idColumn = "item_id", keyColumn = "position") val vendors: List<ItemVendorEntity>,
    @Version val version: Int = 0,
    @Embedded.Empty val audit: AuditMetadata = AuditMetadata(),
)

@Table(schema = "catalog", name = "item_upc")
data class ItemUpcEntity(
    val upc: String,
)

@Table(schema = "catalog", name = "item_pack")
data class ItemPackEntity(
    val unit: String,
    val baseUnits: BigDecimal,
)

@Table(schema = "catalog", name = "item_vendor")
data class ItemVendorEntity(
    val vendorId: UUID,
    val vendorSku: String,
    val casePack: Int,
    val listCost: BigDecimal,
    val currency: String,
)

interface ItemJdbcRepository :
    CrudRepository<ItemEntity, UUID>,
    PagingAndSortingRepository<ItemEntity, UUID> {
    fun existsByNumber(number: String): Boolean
}

@Repository
class JdbcItemRepository(
    private val items: ItemJdbcRepository,
    private val jdbc: JdbcClient,
) : ItemRepository {
    override fun save(item: Item) {
        val audit = if (item.version == 0) AuditMetadata() else items.findById(item.id.value).orElseThrow().audit
        items.save(item.toEntity(audit))
    }

    override fun findById(id: ItemId): Item? = items.findById(id.value).getOrNull()?.toDomain()

    override fun existsByNumber(number: ItemNumber): Boolean = items.existsByNumber(number.value)

    override fun findOwners(upcs: Collection<Upc>): Map<Upc, ItemId> {
        if (upcs.isEmpty()) return emptyMap()
        return jdbc
            .sql("SELECT upc, item_id FROM catalog.item_upc WHERE upc IN (:upcs)")
            .param("upcs", upcs.map { it.value })
            .query { row, _ -> Upc.of(row.getString("upc")) to ItemId.of(row.getObject("item_id", UUID::class.java)) }
            .list()
            .toMap()
    }

    override fun findPage(query: PageQuery): Page<Item> {
        val page = items.findAll(PageRequest.of(query.page, query.size, Sort.by("number")))
        return Page(page.content.map { it.toDomain() }, query.page, query.size, page.totalElements)
    }

    private fun Item.toEntity(audit: AuditMetadata) =
        ItemEntity(
            id = id.value,
            number = number.value,
            description = details.description.value,
            categoryCode = details.category.code.value,
            departmentCode = details.category.departmentCode.value,
            baseUnit = details.units.baseUnit.code,
            discontinuedOn = discontinuedOn?.value,
            upcs = details.upcs.map { ItemUpcEntity(it.value) }.toSet(),
            packs = details.units.packs.map { ItemPackEntity(it.unit.code, it.baseUnits) },
            vendors =
                vendorItems.map {
                    ItemVendorEntity(
                        it.vendorId.value,
                        it.vendorSku,
                        it.casePack,
                        it.listCost.amount,
                        it.listCost.currency.currencyCode,
                    )
                },
            version = version,
            audit = audit,
        )

    private fun ItemEntity.toDomain(): Item =
        Item.rehydrate(
            id = ItemId.of(id),
            number = ItemNumber.of(number),
            details =
                ItemDetails(
                    description = ItemDescription.of(description),
                    category = ItemCategory(CategoryCode.of(categoryCode), DepartmentCode.of(departmentCode)),
                    units =
                        ItemUnits.of(
                            UnitOfMeasure.of(baseUnit),
                            packs.map { Pack.of(UnitOfMeasure.of(it.unit), it.baseUnits) },
                        ),
                    upcs = upcs.map { Upc.of(it.upc) }.toSet(),
                ),
            vendorItems =
                vendors.map {
                    VendorItem.of(
                        VendorId.of(it.vendorId),
                        it.vendorSku,
                        it.casePack,
                        Money.of(it.listCost, Currency.getInstance(it.currency)),
                    )
                },
            discontinuedOn = discontinuedOn?.let(::BusinessDate),
            version = version,
        )
}
