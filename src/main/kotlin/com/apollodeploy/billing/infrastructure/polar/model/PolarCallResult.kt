package com.apollodeploy.billing.infrastructure.polar.model

data class PolarCallResult<out T>(
    val value: T? = null,
    val statusCode: Int? = null,
    val errorBody: String? = null,
) {
    companion object {
        fun <T> success(
            value: T,
            statusCode: Int,
        ): PolarCallResult<T> = PolarCallResult(value = value, statusCode = statusCode)

        fun <T> failure(
            statusCode: Int?,
            errorBody: String,
        ): PolarCallResult<T> = PolarCallResult(statusCode = statusCode, errorBody = errorBody)
    }
}
