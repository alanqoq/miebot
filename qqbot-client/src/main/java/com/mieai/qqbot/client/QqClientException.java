package com.mieai.qqbot.client;

import com.mieai.qqbot.protocol.error.QqApiError;
import java.net.URI;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/** Sanitized error returned by all HTTP operations in this module. */
public final class QqClientException extends RuntimeException {
    private final QqClientFailure failure;
    private final URI endpoint;
    private final int httpStatus;
    private final Integer qqCode;
    private final String qqMessage;
    private final String qqTraceId;

    private QqClientException(
            String message,
            Throwable cause,
            QqClientFailure failure,
            URI endpoint,
            int httpStatus,
            Integer qqCode,
            String qqMessage,
            String qqTraceId) {
        super(message, cause);
        this.failure = Objects.requireNonNull(failure, "failure must not be null");
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint must not be null");
        this.httpStatus = httpStatus;
        this.qqCode = qqCode;
        this.qqMessage = qqMessage;
        this.qqTraceId = qqTraceId;
    }

    public QqClientFailure failure() {
        return failure;
    }

    public URI endpoint() {
        return endpoint;
    }

    public OptionalInt httpStatus() {
        return httpStatus < 0 ? OptionalInt.empty() : OptionalInt.of(httpStatus);
    }

    public OptionalInt qqCode() {
        return qqCode == null ? OptionalInt.empty() : OptionalInt.of(qqCode);
    }

    public Optional<String> qqMessage() {
        return Optional.ofNullable(qqMessage);
    }

    public Optional<String> qqTraceId() {
        return Optional.ofNullable(qqTraceId);
    }

    static QqClientException httpStatus(URI endpoint, int status, QqApiError error) {
        Integer code = error == null ? null : error.code();
        String message = "QQ request failed with HTTP status " + status;
        return new QqClientException(
                message,
                null,
                QqClientFailure.HTTP_STATUS,
                endpoint,
                status,
                code,
                error == null ? null : error.message(),
                error == null ? null : error.traceId());
    }

    static QqClientException authenticationRejected(URI endpoint, int qqCode) {
        return new QqClientException(
                "QQ rejected the bot credentials",
                null,
                QqClientFailure.AUTHENTICATION,
                endpoint,
                -1,
                qqCode,
                null,
                null);
    }

    static QqClientException timeout(URI endpoint, Throwable cause) {
        return new QqClientException(
                "QQ request timed out",
                cause,
                QqClientFailure.TIMEOUT,
                endpoint,
                -1,
                null,
                null,
                null);
    }

    static QqClientException transport(URI endpoint, Throwable cause) {
        return new QqClientException(
                "QQ request failed before receiving an HTTP response",
                cause,
                QqClientFailure.TRANSPORT,
                endpoint,
                -1,
                null,
                null,
                null);
    }

    static QqClientException protocol(URI endpoint, Throwable cause) {
        return new QqClientException(
                "QQ response did not match the expected protocol",
                cause,
                QqClientFailure.PROTOCOL,
                endpoint,
                -1,
                null,
                null,
                null);
    }
}
