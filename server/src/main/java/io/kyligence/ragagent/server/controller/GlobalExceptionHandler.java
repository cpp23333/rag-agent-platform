package io.kyligence.ragagent.server.controller;

import io.kyligence.ragagent.shared.api.ApiResponse;
import io.kyligence.ragagent.shared.exception.ErrorCode;
import io.kyligence.ragagent.shared.exception.PlatformException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理器
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(PlatformException.class)
    public ResponseEntity<ApiResponse<Void>> handlePlatform(PlatformException ex) {
        ErrorCode errorCode = ex.getErrorCode();
        HttpStatus status = switch (errorCode) {
            case UNAUTHORIZED -> HttpStatus.UNAUTHORIZED;
            case INSUFFICIENT_PERMISSIONS -> HttpStatus.FORBIDDEN;
            case RESOURCE_NOT_FOUND, USER_NOT_FOUND, WORKSPACE_NOT_FOUND,
                 KNOWLEDGE_BASE_NOT_FOUND, AGENT_NOT_FOUND, WORKFLOW_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case USER_ALREADY_EXISTS, WORKSPACE_ALREADY_EXISTS -> HttpStatus.CONFLICT;
            case INVALID_REQUEST, VALIDATION_FAILED -> HttpStatus.BAD_REQUEST;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
        return ResponseEntity.status(status)
            .body(ApiResponse.error(errorCode.getCode(), ex.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleAny(Exception ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ApiResponse.error(ErrorCode.INTERNAL_SERVER_ERROR.getCode(), ex.getMessage()));
    }
}
