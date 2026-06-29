package com.apollodeploy.billing.infrastructure.resilience

import arrow.resilience.CircuitBreaker
import arrow.resilience.CircuitBreaker.OpeningStrategy
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.seconds

/**
 * Apollo Billing — Arrow CircuitBreaker for Polar API calls.
 *
 * Wraps outbound calls to Polar with Arrow's CircuitBreaker to prevent
 * cascading failures when Polar is experiencing issues.
 *
 * States:
 *   - Closed: normal operation, requests pass through
 *   - Open: Polar is down, requests short-circuit immediately (use Redis fallback)
 *   - HalfOpen: after reset timeout, one test request is allowed through
 *
 * Configuration:
 *   - Opens after 5 consecutive failures
 *   - Reset timeout: 30 seconds (try Polar again after 30s)
 *   - Exponential backoff factor: 2.0 (double the wait on each subsequent failure)
 *   - Max reset timeout: 5 minutes (never wait more than 5 min before retrying)
 */
object PolarCircuitBreakerFactory {
    private val logger = LoggerFactory.getLogger(PolarCircuitBreakerFactory::class.java)

    suspend fun create(): CircuitBreaker {
        val cb =
            CircuitBreaker(
                openingStrategy = OpeningStrategy.Count(maxFailures = 5),
                resetTimeout = 30.seconds,
                exponentialBackoffFactor = 2.0,
                maxResetTimeout = 300.seconds,
            )
        logger.info("[billing:resilience] Polar CircuitBreaker created (opens after 5 failures, 30s reset)")
        return cb
    }
}
