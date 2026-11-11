package com.retailplatform.catalog.application

import com.retailplatform.catalog.domain.Category
import com.retailplatform.catalog.domain.CategoryCode
import com.retailplatform.catalog.domain.Department
import com.retailplatform.catalog.domain.DepartmentCode
import com.retailplatform.catalog.domain.Item
import com.retailplatform.catalog.domain.ItemNumber
import com.retailplatform.catalog.domain.UnitDefinition
import com.retailplatform.catalog.domain.Upc
import com.retailplatform.shared.ItemId
import com.retailplatform.shared.Page
import com.retailplatform.shared.PageQuery
import com.retailplatform.shared.UnitOfMeasure

/** Port: where departments and categories are kept. */
interface HierarchyRepository {
    fun addDepartment(department: Department)

    fun addCategory(category: Category)

    fun existsDepartment(code: DepartmentCode): Boolean

    fun findCategory(code: CategoryCode): Category?

    /** Departments ordered by code. */
    fun findDepartments(): List<Department>

    /** The categories of one department, ordered by code. */
    fun findCategories(departmentCode: DepartmentCode): List<Category>
}

/** Port: the units of measure the catalog knows. */
interface UnitOfMeasureRepository {
    fun add(definition: UnitDefinition)

    /** The subset of [units] that are defined. */
    fun findDefined(units: Collection<UnitOfMeasure>): Set<UnitOfMeasure>

    /** Units ordered by code. */
    fun findAll(): List<UnitDefinition>
}

/** Port: where items are kept. */
interface ItemRepository {
    /** Inserts a new item or updates an existing one; a stale version is refused. */
    fun save(item: Item)

    fun findById(id: ItemId): Item?

    fun existsByNumber(number: ItemNumber): Boolean

    /** Which of [upcs] are already on an item, and on which item. */
    fun findOwners(upcs: Collection<Upc>): Map<Upc, ItemId>

    /** Items ordered by number. */
    fun findPage(query: PageQuery): Page<Item>
}
