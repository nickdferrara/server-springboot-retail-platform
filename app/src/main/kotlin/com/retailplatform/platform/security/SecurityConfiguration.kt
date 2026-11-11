package com.retailplatform.platform.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.aop.Advisor
import org.springframework.aop.support.ComposablePointcut
import org.springframework.aop.support.annotation.AnnotationMatchingPointcut
import org.springframework.beans.factory.config.BeanDefinition
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.context.annotation.Role
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.authorization.method.AuthorizationManagerBeforeMethodInterceptor
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.invoke
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter
import org.springframework.web.filter.OncePerRequestFilter
import tools.jackson.databind.json.JsonMapper

/**
 * Who may call the API.
 *
 * Outside the `local` profile every request except the health probes needs an access token from Microsoft Entra ID.
 * Roles are then checked on the use case with [RequiresRole].
 */
@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
@EnableConfigurationProperties(LocalDeveloperProperties::class)
class SecurityConfiguration {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Enforces [RequiresRole] on every bean method that carries it, directly or through its class. */
    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    fun requiresRoleAdvisor(): Advisor {
        val annotatedMethodOrClass =
            ComposablePointcut(AnnotationMatchingPointcut(null, RequiresRole::class.java, true))
                .union(AnnotationMatchingPointcut(RequiresRole::class.java, true))
        return AuthorizationManagerBeforeMethodInterceptor(annotatedMethodOrClass, RequiresRoleAuthorizationManager())
    }

    @Bean
    @Profile("!local")
    fun apiSecurity(
        http: HttpSecurity,
        json: JsonMapper,
    ): SecurityFilterChain {
        http {
            statelessApi()
            oauth2ResourceServer {
                jwt { jwtAuthenticationConverter = EntraJwtAuthenticationConverter() }
                authenticationEntryPoint = ProblemAuthenticationEntryPoint(json)
                accessDeniedHandler = ProblemAccessDeniedHandler(json)
            }
            exceptionHandling {
                authenticationEntryPoint = ProblemAuthenticationEntryPoint(json)
                accessDeniedHandler = ProblemAccessDeniedHandler(json)
            }
        }
        return http.build()
    }

    /** The `local` profile has no sign-in, so the application starts and runs without Azure. */
    @Bean
    @Profile("local")
    fun localDeveloperSecurity(
        http: HttpSecurity,
        json: JsonMapper,
        developer: LocalDeveloperProperties,
    ): SecurityFilterChain {
        log.warn("Sign-in is OFF: every request runs as '{}' with roles {}", developer.user, developer.roles)
        http {
            statelessApi()
            addFilterBefore<AnonymousAuthenticationFilter>(LocalDeveloperFilter(developer))
            exceptionHandling { accessDeniedHandler = ProblemAccessDeniedHandler(json) }
        }
        return http.build()
    }

    private fun org.springframework.security.config.annotation.web.HttpSecurityDsl.statelessApi() {
        csrf { disable() }
        sessionManagement { sessionCreationPolicy = SessionCreationPolicy.STATELESS }
        authorizeHttpRequests {
            authorize("/actuator/health/**", permitAll)
            authorize(anyRequest, authenticated)
        }
    }
}

/** Signs every request in as the configured local developer. */
private class LocalDeveloperFilter(
    private val developer: LocalDeveloperProperties,
) : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken.authenticated(
                developer.user,
                "n/a",
                developer.roles.map(::SimpleGrantedAuthority),
            )
        filterChain.doFilter(request, response)
    }
}
