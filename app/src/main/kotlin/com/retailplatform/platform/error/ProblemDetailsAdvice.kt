package com.retailplatform.platform.error

import com.retailplatform.platform.web.InvalidRequestValue
import com.retailplatform.shared.BusinessRuleViolation
import com.retailplatform.shared.ResourceNotFound
import org.slf4j.LoggerFactory
import org.springframework.context.i18n.LocaleContextHolder
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatusCode
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.AuthenticationException
import org.springframework.web.ErrorResponse
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.WebRequest
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler

/**
 * The single place where failures become HTTP responses. Every error body is an RFC 9457 problem document with a
 * stable `type` URI and a `code` property.
 */
@RestControllerAdvice
class ProblemDetailsAdvice : ResponseEntityExceptionHandler() {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun handleMethodArgumentNotValid(
        ex: MethodArgumentNotValidException,
        headers: HttpHeaders,
        status: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any>? {
        val problem = ProblemType.VALIDATION_FAILED.problem("One or more fields are invalid.")
        problem.setProperty(
            "errors",
            ex.bindingResult.fieldErrors.map { mapOf("field" to it.field, "message" to it.defaultMessage) },
        )
        return handleExceptionInternal(ex, problem, headers, status, request)
    }

    override fun handleHttpMessageNotReadable(
        ex: HttpMessageNotReadableException,
        headers: HttpHeaders,
        status: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any>? {
        val problem = ProblemType.MALFORMED_REQUEST.problem("The request body could not be read.")
        return handleExceptionInternal(ex, problem, headers, status, request)
    }

    /** A value object refused the input while a request was being mapped to a command. */
    @ExceptionHandler(InvalidRequestValue::class)
    fun invalidValue(ex: InvalidRequestValue): ResponseEntity<ProblemDetail> =
        response(ProblemType.INVALID_VALUE.problem(ex.message ?: "A value in the request is invalid."))

    @ExceptionHandler(BusinessRuleViolation::class)
    fun businessRuleViolation(ex: BusinessRuleViolation): ResponseEntity<ProblemDetail> {
        val problem = ProblemType.BUSINESS_RULE_VIOLATION.problem(ex.message ?: "A business rule was violated.")
        return response(ProblemType.withCode(problem, ex.code))
    }

    @ExceptionHandler(ResourceNotFound::class)
    fun notFound(ex: ResourceNotFound): ResponseEntity<ProblemDetail> =
        response(ProblemType.NOT_FOUND.problem("${ex.resource} ${ex.id} was not found."))

    @ExceptionHandler(OptimisticLockingFailureException::class)
    fun staleWrite(): ResponseEntity<ProblemDetail> =
        response(ProblemType.CONFLICT.problem("The record was changed by someone else. Reload it and try again."))

    @ExceptionHandler(DataIntegrityViolationException::class)
    fun constraintViolated(ex: DataIntegrityViolationException): ResponseEntity<ProblemDetail> {
        log.warn("A database constraint rejected a write", ex)
        return response(ProblemType.CONFLICT.problem("The change conflicts with existing data."))
    }

    @ExceptionHandler(Exception::class)
    fun unexpected(ex: Exception): ResponseEntity<ProblemDetail> {
        // Sign-in and permission failures belong to the security filter chain, which answers 401 or 403.
        if (ex is AuthenticationException || ex is AccessDeniedException) throw ex
        log.error("Unhandled failure", ex)
        return response(ProblemType.INTERNAL_ERROR.problem("An unexpected error occurred."))
    }

    /** Gives the framework's own errors (405, 406, 415, and so on) the same `type` and `code` shape. */
    override fun handleExceptionInternal(
        ex: Exception,
        body: Any?,
        headers: HttpHeaders,
        statusCode: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any>? {
        val problem = body ?: (ex as? ErrorResponse)?.updateAndGetBody(messageSource, LocaleContextHolder.getLocale())
        if (problem is ProblemDetail) ProblemType.describeFrameworkProblem(problem, statusCode)
        return super.handleExceptionInternal(ex, problem, headers, statusCode, request)
    }

    private fun response(problem: ProblemDetail): ResponseEntity<ProblemDetail> =
        ResponseEntity.status(problem.status).body(problem)
}
