package com.retailplatform.platform.error

import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.ProblemDetail
import java.net.URI

private const val URI_PREFIX = "urn:retail-platform:problem:"
private const val CODE = "code"

/** The kinds of error the API reports. Each has a stable `type` URI and `code` that clients can branch on. */
enum class ProblemType(
    val status: HttpStatus,
    val code: String,
    val title: String,
) {
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "validation-failed", "Validation failed"),
    MALFORMED_REQUEST(HttpStatus.BAD_REQUEST, "malformed-request", "Malformed request"),
    INVALID_VALUE(HttpStatus.BAD_REQUEST, "invalid-value", "Invalid value"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "not-found", "Not found"),
    CONFLICT(HttpStatus.CONFLICT, "conflict", "Conflict"),
    BUSINESS_RULE_VIOLATION(HttpStatus.UNPROCESSABLE_CONTENT, "business-rule-violation", "Business rule violation"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "internal-error", "Internal error"),
    ;

    /** A problem document of this kind. */
    fun problem(detail: String): ProblemDetail =
        ProblemDetail.forStatusAndDetail(status, detail).also {
            it.type = URI.create(URI_PREFIX + code)
            it.title = title
            it.setProperty(CODE, code)
        }

    companion object {
        /** Replaces the `code` of [problem], keeping its `type`; used for a business rule's own code. */
        fun withCode(
            problem: ProblemDetail,
            code: String,
        ): ProblemDetail = problem.also { it.setProperty(CODE, code) }

        /** Gives a problem raised by the web framework a `type` and `code` derived from its HTTP status. */
        fun describeFrameworkProblem(
            problem: ProblemDetail,
            status: HttpStatusCode,
        ) {
            if (problem.properties?.containsKey(CODE) == true) return
            val code =
                HttpStatus
                    .resolve(status.value())
                    ?.name
                    ?.lowercase()
                    ?.replace('_', '-') ?: "http-${status.value()}"
            problem.type = URI.create(URI_PREFIX + code)
            problem.setProperty(CODE, code)
        }
    }
}
