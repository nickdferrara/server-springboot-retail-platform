package com.retailplatform.catalog.adapter.`in`.web

import com.retailplatform.catalog.application.CreateCategory
import com.retailplatform.catalog.application.CreateDepartment
import com.retailplatform.catalog.application.DefineUnitOfMeasure
import com.retailplatform.catalog.application.ReferenceDataQueries
import com.retailplatform.catalog.domain.CatalogName
import com.retailplatform.catalog.domain.Category
import com.retailplatform.catalog.domain.CategoryCode
import com.retailplatform.catalog.domain.Department
import com.retailplatform.catalog.domain.DepartmentCode
import com.retailplatform.platform.web.mapRequest
import com.retailplatform.shared.UnitOfMeasure
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/** A code and a name: the JSON shape of a department, a category, and a unit of measure. */
data class CodeAndName(
    @field:NotBlank
    @field:Pattern(
        regexp = "[A-Z][A-Z0-9]{0,9}|[A-Z0-9]{1,10}",
        message = "must be 1 to 10 upper-case letters or digits",
    )
    val code: String?,
    @field:NotBlank
    @field:Size(max = CatalogName.MAX_LENGTH)
    val name: String?,
)

data class CategoryResponse(
    val code: String,
    val departmentCode: String,
    val name: String,
)

@RestController
@RequestMapping("/api/v1")
class ReferenceDataController(
    private val createDepartment: CreateDepartment,
    private val createCategory: CreateCategory,
    private val defineUnitOfMeasure: DefineUnitOfMeasure,
    private val queries: ReferenceDataQueries,
) {
    @PostMapping("/departments")
    @ResponseStatus(HttpStatus.CREATED)
    fun createDepartment(
        @Valid @RequestBody request: CodeAndName,
    ): CodeAndName {
        val department =
            mapRequest {
                Department(
                    DepartmentCode.of(requireNotNull(request.code)),
                    CatalogName.of(requireNotNull(request.name)),
                )
            }
        createDepartment.handle(department)
        return CodeAndName(department.code.value, department.name.value)
    }

    @GetMapping("/departments")
    fun departments(): List<CodeAndName> = queries.departments().map { CodeAndName(it.code.value, it.name.value) }

    @PostMapping("/departments/{departmentCode}/categories")
    @ResponseStatus(HttpStatus.CREATED)
    fun createCategory(
        @PathVariable departmentCode: String,
        @Valid @RequestBody request: CodeAndName,
    ): CategoryResponse {
        val category =
            mapRequest {
                Category(
                    CategoryCode.of(requireNotNull(request.code)),
                    DepartmentCode.of(departmentCode),
                    CatalogName.of(requireNotNull(request.name)),
                )
            }
        createCategory.handle(category)
        return category.toResponse()
    }

    @GetMapping("/departments/{departmentCode}/categories")
    fun categories(
        @PathVariable departmentCode: String,
    ): List<CategoryResponse> =
        queries.categories(mapRequest { DepartmentCode.of(departmentCode) }).map { it.toResponse() }

    @PostMapping("/units-of-measure")
    @ResponseStatus(HttpStatus.CREATED)
    fun defineUnit(
        @Valid @RequestBody request: CodeAndName,
    ): CodeAndName {
        val (unit, name) =
            mapRequest {
                UnitOfMeasure.of(requireNotNull(request.code)) to CatalogName.of(requireNotNull(request.name))
            }
        defineUnitOfMeasure.handle(unit, name)
        return CodeAndName(unit.code, name.value)
    }

    @GetMapping("/units-of-measure")
    fun units(): List<CodeAndName> = queries.unitsOfMeasure().map { CodeAndName(it.unit.code, it.name.value) }

    private fun Category.toResponse() = CategoryResponse(code.value, departmentCode.value, name.value)
}
