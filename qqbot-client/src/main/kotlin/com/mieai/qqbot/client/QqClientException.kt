package com.mieai.qqbot.client

import com.mieai.qqbot.protocol.error.QqApiError
import java.net.URI

/** Sanitized error returned by all HTTP operations in this module. */
class QqClientException private constructor(
    message: String,
    cause: Throwable?,
    val failure: QqClientFailure,
    val endpoint: URI,
    val httpStatus: Int?,
    val qqCode: Int?,
    val qqMessage: String?,
    val qqTraceId: String?,
) : RuntimeException(message, cause) {
    companion object {
        internal fun httpStatus(endpoint: URI, status: Int, error: QqApiError?): QqClientException =
            QqClientException(
                "QQ request failed with HTTP status $status",
                null,
                QqClientFailure.HTTP_STATUS,
                endpoint,
                status,
                error?.code,
                error?.message,
                error?.traceId,
            )

        internal fun authenticationRejected(endpoint: URI, qqCode: Int): QqClientException =
            QqClientException(
                "QQ rejected the bot credentials",
                null,
                QqClientFailure.AUTHENTICATION,
                endpoint,
                null,
                qqCode,
                null,
                null,
            )

        internal fun timeout(endpoint: URI, cause: Throwable): QqClientException =
            QqClientException(
                "QQ request timed out",
                cause,
                QqClientFailure.TIMEOUT,
                endpoint,
                null,
                null,
                null,
                null,
            )

        internal fun transport(endpoint: URI, cause: Throwable): QqClientException =
            QqClientException(
                "QQ request failed before receiving an HTTP response",
                cause,
                QqClientFailure.TRANSPORT,
                endpoint,
                null,
                null,
                null,
                null,
            )

        internal fun protocol(endpoint: URI, cause: Throwable): QqClientException =
            QqClientException(
                "QQ response did not match the expected protocol",
                cause,
                QqClientFailure.PROTOCOL,
                endpoint,
                null,
                null,
                null,
                null,
            )
    }
}
