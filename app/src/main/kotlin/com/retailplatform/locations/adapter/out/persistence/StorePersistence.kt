package com.retailplatform.locations.adapter.out.persistence

import com.retailplatform.locations.application.StoreRepository
import com.retailplatform.locations.domain.LocationName
import com.retailplatform.locations.domain.Store
import com.retailplatform.locations.domain.StoreNumber
import com.retailplatform.platform.persistence.AuditMetadata
import com.retailplatform.shared.BusinessDate
import com.retailplatform.shared.Page
import com.retailplatform.shared.PageQuery
import com.retailplatform.shared.StoreId
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.Version
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.data.relational.core.mapping.Embedded
import org.springframework.data.relational.core.mapping.Table
import org.springframework.data.repository.CrudRepository
import org.springframework.data.repository.PagingAndSortingRepository
import org.springframework.stereotype.Repository
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlin.jvm.optionals.getOrNull

@Table(schema = "locations", name = "store")
data class StoreEntity(
    @Id val id: UUID,
    val number: String,
    val name: String,
    val timeZone: String,
    val openedOn: LocalDate,
    val closedOn: LocalDate?,
    @Version val version: Int = 0,
    @Embedded.Empty val audit: AuditMetadata = AuditMetadata(),
)

interface StoreJdbcRepository :
    CrudRepository<StoreEntity, UUID>,
    PagingAndSortingRepository<StoreEntity, UUID> {
    fun existsByNumber(number: String): Boolean
}

@Repository
class JdbcStoreRepository(
    private val stores: StoreJdbcRepository,
) : StoreRepository {
    override fun save(store: Store) {
        // An update keeps the stored creation stamp; the version check refuses a stale write.
        val audit = if (store.version == 0) AuditMetadata() else stores.findById(store.id.value).orElseThrow().audit
        stores.save(
            StoreEntity(
                id = store.id.value,
                number = store.number.value,
                name = store.name.value,
                timeZone = store.timeZone.id,
                openedOn = store.openedOn.value,
                closedOn = store.closedOn?.value,
                version = store.version,
                audit = audit,
            ),
        )
    }

    override fun findById(id: StoreId): Store? = stores.findById(id.value).getOrNull()?.toDomain()

    override fun findAllById(ids: Collection<StoreId>): List<Store> =
        stores.findAllById(ids.map { it.value }).map { it.toDomain() }

    override fun existsByNumber(number: StoreNumber): Boolean = stores.existsByNumber(number.value)

    override fun findPage(query: PageQuery): Page<Store> {
        val page = stores.findAll(PageRequest.of(query.page, query.size, Sort.by("number")))
        return Page(page.content.map { it.toDomain() }, query.page, query.size, page.totalElements)
    }

    private fun StoreEntity.toDomain(): Store =
        Store.rehydrate(
            id = StoreId.of(id),
            number = StoreNumber.of(number),
            name = LocationName.of(name),
            timeZone = ZoneId.of(timeZone),
            openedOn = BusinessDate(openedOn),
            closedOn = closedOn?.let(::BusinessDate),
            version = version,
        )
}
