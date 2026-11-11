package com.retailplatform.catalog.application

import com.retailplatform.catalog.domain.CatalogName
import com.retailplatform.catalog.domain.Category
import com.retailplatform.catalog.domain.CategoryCode
import com.retailplatform.catalog.domain.CategoryCodeAlreadyInUse
import com.retailplatform.catalog.domain.Department
import com.retailplatform.catalog.domain.DepartmentCode
import com.retailplatform.catalog.domain.DepartmentCodeAlreadyInUse
import com.retailplatform.catalog.domain.UnitDefinition
import com.retailplatform.catalog.domain.UnitOfMeasureAlreadyDefined
import com.retailplatform.catalog.domain.UnknownDepartment
import com.retailplatform.platform.security.RequiresRole
import com.retailplatform.platform.security.Roles
import com.retailplatform.shared.UnitOfMeasure
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** Use case: add a department to the item hierarchy. */
@Service
class CreateDepartment(
    private val hierarchy: HierarchyRepository,
) {
    @RequiresRole(Roles.MASTER_DATA_EDITOR)
    @Transactional
    fun handle(department: Department) {
        if (hierarchy.existsDepartment(department.code)) throw DepartmentCodeAlreadyInUse(department.code)
        hierarchy.addDepartment(department)
    }
}

/** Use case: add a category to a department. */
@Service
class CreateCategory(
    private val hierarchy: HierarchyRepository,
) {
    @RequiresRole(Roles.MASTER_DATA_EDITOR)
    @Transactional
    fun handle(category: Category) {
        if (!hierarchy.existsDepartment(category.departmentCode)) throw UnknownDepartment(category.departmentCode)
        if (hierarchy.findCategory(category.code) != null) throw CategoryCodeAlreadyInUse(category.code)
        hierarchy.addCategory(category)
    }
}

/** Use case: define a unit of measure items may use. */
@Service
class DefineUnitOfMeasure(
    private val units: UnitOfMeasureRepository,
) {
    @RequiresRole(Roles.MASTER_DATA_EDITOR)
    @Transactional
    fun handle(
        unit: UnitOfMeasure,
        name: CatalogName,
    ) {
        if (units.findDefined(listOf(unit)).isNotEmpty()) throw UnitOfMeasureAlreadyDefined(unit.code)
        units.add(UnitDefinition(unit, name))
    }
}

/** Queries: the item hierarchy and the units of measure. */
@Service
@Transactional(readOnly = true)
class ReferenceDataQueries(
    private val hierarchy: HierarchyRepository,
    private val units: UnitOfMeasureRepository,
) {
    fun departments(): List<Department> = hierarchy.findDepartments()

    fun categories(departmentCode: DepartmentCode): List<Category> {
        if (!hierarchy.existsDepartment(departmentCode)) throw UnknownDepartment(departmentCode)
        return hierarchy.findCategories(departmentCode)
    }

    fun category(code: CategoryCode): Category? = hierarchy.findCategory(code)

    fun unitsOfMeasure(): List<UnitDefinition> = units.findAll()
}
