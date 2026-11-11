package com.retailplatform.catalog.domain

import com.retailplatform.shared.BusinessRuleViolation
import com.retailplatform.shared.UnitOfMeasure

/** The top level of the item hierarchy, such as Grocery or Tobacco. */
data class Department(
    val code: DepartmentCode,
    val name: CatalogName,
)

/** A group of similar items within a department, such as Salty Snacks. */
data class Category(
    val code: CategoryCode,
    val departmentCode: DepartmentCode,
    val name: CatalogName,
) {
    /** Where an item in this category sits in the hierarchy. */
    fun placement(): ItemCategory = ItemCategory(code, departmentCode)
}

/** An item's place in the hierarchy: its category and that category's department. */
data class ItemCategory(
    val code: CategoryCode,
    val departmentCode: DepartmentCode,
)

class DepartmentCodeAlreadyInUse(
    code: DepartmentCode,
) : BusinessRuleViolation("catalog.department-code-in-use", "Department code $code is already in use")

class CategoryCodeAlreadyInUse(
    code: CategoryCode,
) : BusinessRuleViolation("catalog.category-code-in-use", "Category code $code is already in use")

class UnitOfMeasureAlreadyDefined(
    code: String,
) : BusinessRuleViolation("catalog.unit-already-defined", "Unit of measure $code is already defined")

/** A unit of measure the catalog knows, with its display name. */
data class UnitDefinition(
    val unit: UnitOfMeasure,
    val name: CatalogName,
)

class UnknownCategory(
    code: CategoryCode,
) : BusinessRuleViolation("catalog.unknown-category", "Category $code does not exist")

class UnknownDepartment(
    code: DepartmentCode,
) : BusinessRuleViolation("catalog.unknown-department", "Department $code does not exist")
