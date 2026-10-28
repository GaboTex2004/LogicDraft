package com.sw1.backend.ai.client;

import org.springframework.http.HttpStatus;

public class AiServiceException extends RuntimeException {
    public enum ErrorCategory {
        INVALID_UML_DOCUMENT,
        SEMANTIC_CONVERTER_ERROR,
        FASTAPI_COMMUNICATION_ERROR,
        AI_PROVIDER_ERROR,
        INVALID_MODEL_RESPONSE,
        OPERATION_VALIDATION_ERROR
    }

    private final HttpStatus status;
    private final ErrorCategory category;
    private final String technicalDetail;

    public AiServiceException(HttpStatus status, String message) {
        this(status, inferDefaultCategory(status), message, message);
    }

    public AiServiceException(HttpStatus status, ErrorCategory category, String message) {
        this(status, category, message, message);
    }

    public AiServiceException(HttpStatus status, ErrorCategory category, String message, String technicalDetail) {
        super(message);
        this.status = status;
        this.category = category == null ? inferDefaultCategory(status) : category;
        this.technicalDetail = (technicalDetail == null || technicalDetail.isBlank()) ? message : technicalDetail;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public ErrorCategory getCategory() {
        return category;
    }

    public String getTechnicalDetail() {
        return technicalDetail;
    }

    private static ErrorCategory inferDefaultCategory(HttpStatus status) {
        if (status == HttpStatus.SERVICE_UNAVAILABLE || status == HttpStatus.GATEWAY_TIMEOUT) {
            return ErrorCategory.AI_PROVIDER_ERROR;
        }
        if (status == HttpStatus.BAD_GATEWAY || status == HttpStatus.UNPROCESSABLE_ENTITY) {
            return ErrorCategory.INVALID_MODEL_RESPONSE;
        }
        return ErrorCategory.OPERATION_VALIDATION_ERROR;
    }
}
