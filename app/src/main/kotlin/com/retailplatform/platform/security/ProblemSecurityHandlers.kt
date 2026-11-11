package com.retailplatform.platform.security

import com.retailplatform.platform.error.ProblemType
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.AuthenticationException
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.access.AccessDeniedHandler
import tools.jackson.databind.json.JsonMapper

/** Answers a request with no valid token: 401 with a `WWW-Authenticate` header and a problem document. */
class ProblemAuthenticationEntryPoint(
    private val json: JsonMapper,
) : AuthenticationEntryPoint {
    private val bearer = BearerTokenAuthenticationEntryPoint()

    override fun commence(
        request: HttpServletRequest,
        response: HttpServletResponse,
        authException: AuthenticationException,
    ) {
        bearer.commence(request, response, authException)
        response.writeProblem(json, ProblemType.UNAUTHENTICATED.problem("A valid access token is required."))
    }
}

/** Answers a signed-in user who lacks the role for the request: 403 with a problem document. */
class ProblemAccessDeniedHandler(
    private val json: JsonMapper,
) : AccessDeniedHandler {
    private val bearer = BearerTokenAccessDeniedHandler()

    override fun handle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        accessDeniedException: AccessDeniedException,
    ) {
        bearer.handle(request, response, accessDeniedException)
        response.writeProblem(json, ProblemType.FORBIDDEN.problem("Your roles do not allow this request."))
    }
}

private fun HttpServletResponse.writeProblem(
    json: JsonMapper,
    problem: ProblemDetail,
) {
    contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
    json.writeValue(outputStream, problem)
}
