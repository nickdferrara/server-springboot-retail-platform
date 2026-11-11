package com.retailplatform.catalog.adapter.out.persistence

import com.retailplatform.catalog.application.HierarchyRepository
import com.retailplatform.catalog.application.UnitOfMeasureRepository
import com.retailplatform.catalog.domain.CatalogName
import com.retailplatform.catalog.domain.Category
import com.retailplatform.catalog.domain.CategoryCode
import com.retailplatform.catalog.domain.Department
import com.retailplatform.catalog.domain.DepartmentCode
import com.retailplatform.catalog.domain.UnitDefinition
import com.retailplatform.platform.persistence.AuditMetadata
import com.retailplatform.shared.UnitOfMeasure
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.Version
import org.springframework.data.relational.core.mapping.Embedded
import org.springframework.data.relational.core.mapping.Table
import org.springframework.data.repository.CrudRepository
import org.springframework.stereotype.Repository
import kotlin.jvm.optionals.getOrNull

@Table(schema = "catalog", name = "department")
data class DepartmentEntity(
    @Id val code: String,
    val name: String,
    @Version val version: Int = 0,
    @Embedded.Empty val audit: AuditMetadata = AuditMetadata(),
)

@Table(schema = "catalog", name = "category")
data class CategoryEntity(
    @Id val code: String,
    val departmentCode: String,
    val name: String,
    @Version val version: Int = 0,
    @Embedded.Empty val audit: AuditMetadata = AuditMetadata(),
)

@Table(schema = "catalog", name = "unit_of_measure")
data class UnitOfMeasureEntity(
    @Id val code: String,
    val name: String,
    @Version val version: Int = 0,
    @Embedded.Empty val audit: AuditMetadata = AuditMetadata(),
)

interface DepartmentJdbcRepository : CrudRepository<DepartmentEntity, String> {
    fun findAllByOrderByCode(): List<DepartmentEntity>
}

interface CategoryJdbcRepository : CrudRepository<CategoryEntity, String> {
    fun findByDepartmentCodeOrderByCode(departmentCode: String): List<CategoryEntity>
}

interface UnitOfMeasureJdbcRepository : CrudRepository<UnitOfMeasureEntity, String> {
    fun findAllByOrderByCode(): List<UnitOfMeasureEntity>
}

@Repository
class JdbcHierarchyRepository(
    private val departments: DepartmentJdbcRepository,
    private val categories: CategoryJdbcRepository,
) : HierarchyRepository {
    override fun addDepartment(department: Department) {
        departments.save(DepartmentEntity(department.code.value, department.name.value))
    }

    override fun addCategory(category: Category) {
        categories.save(CategoryEntity(category.code.value, category.departmentCode.value, category.name.value))
    }

    override fun existsDepartment(code: DepartmentCode): Boolean = departments.existsById(code.value)

    override fun findCategory(code: CategoryCode): Category? = categories.findById(code.value).getOrNull()?.toDomain()

    override fun findDepartments(): List<Department> =
        departments.findAllByOrderByCode().map { Department(DepartmentCode.of(it.code), CatalogName.of(it.name)) }

    override fun findCategories(departmentCode: DepartmentCode): List<Category> =
        categories.findByDepartmentCodeOrderByCode(departmentCode.value).map { it.toDomain() }

    private fun CategoryEntity.toDomain() =
        Category(CategoryCode.of(code), DepartmentCode.of(departmentCode), CatalogName.of(name))
}

@Repository
class JdbcUnitOfMeasureRepository(
    private val units: UnitOfMeasureJdbcRepository,
) : UnitOfMeasureRepository {
    override fun add(definition: UnitDefinition) {
        units.save(UnitOfMeasureEntity(definition.unit.code, definition.name.value))
    }

    override fun findDefined(units: Collection<UnitOfMeasure>): Set<UnitOfMeasure> =
        this.units
            .findAllById(units.map { it.code })
            .map { UnitOfMeasure.of(it.code) }
            .toSet()

    override fun findAll(): List<UnitDefinition> =
        units.findAllByOrderByCode().map { UnitDefinition(UnitOfMeasure.of(it.code), CatalogName.of(it.name)) }
}
