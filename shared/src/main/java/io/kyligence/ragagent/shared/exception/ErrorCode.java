package io.kyligence.ragagent.shared.exception;

import lombok.Getter;

/**
 * 错误码枚举
 */
@Getter
public enum ErrorCode {
    // 通用错误 (1000-1999)
    INTERNAL_SERVER_ERROR("1000", "Internal server error"),
    INVALID_REQUEST("1001", "Invalid request"),
    RESOURCE_NOT_FOUND("1002", "Resource not found"),
    VALIDATION_FAILED("1003", "Validation failed"),

    // 认证/授权错误 (2000-2999)
    UNAUTHORIZED("2000", "Unauthorized"),
    INVALID_CREDENTIALS("2001", "Invalid credentials"),
    TOKEN_EXPIRED("2002", "Token expired"),
    TOKEN_INVALID("2003", "Token invalid"),
    INSUFFICIENT_PERMISSIONS("2004", "Insufficient permissions"),
    USER_NOT_FOUND("2005", "User not found"),
    USER_ALREADY_EXISTS("2006", "User already exists"),

    // Workspace 错误 (3000-3999)
    WORKSPACE_NOT_FOUND("3000", "Workspace not found"),
    WORKSPACE_ACCESS_DENIED("3001", "Workspace access denied"),
    WORKSPACE_ALREADY_EXISTS("3002", "Workspace already exists"),

    // RAG 错误 (4000-4999)
    KNOWLEDGE_BASE_NOT_FOUND("4000", "Knowledge base not found"),
    INGEST_JOB_FAILED("4001", "Ingest job failed"),

    // Agent 错误 (5000-5999)
    AGENT_NOT_FOUND("5000", "Agent not found"),
    AGENT_RUN_FAILED("5001", "Agent run failed"),
    AGENT_TIMEOUT("5002", "Agent execution timeout"),

    // Workflow 错误 (6000-6999)
    WORKFLOW_NOT_FOUND("6000", "Workflow not found"),
    WORKFLOW_RUN_FAILED("6001", "Workflow run failed"),
    WORKFLOW_VALIDATION_FAILED("6002", "Workflow validation failed"),

    // Platform Core 错误 (7000-7999)
    MODEL_PROVIDER_NOT_FOUND("7000", "Model provider not found"),
    MODEL_CALL_FAILED("7001", "Model call failed"),
    MODEL_TIMEOUT("7002", "Model call timeout"),
    TOOL_NOT_FOUND("7003", "Tool not found"),
    TOOL_INVOKE_FAILED("7004", "Tool invocation failed"),
    PROMPT_NOT_FOUND("7005", "Prompt not found"),
    PROMPT_RENDER_FAILED("7006", "Prompt render failed"),
    SANDBOX_EXECUTION_FAILED("7007", "Sandbox execution failed"),
    SANDBOX_TIMEOUT("7008", "Sandbox execution timeout"),
    SANDBOX_SECURITY_VIOLATION("7009", "Sandbox security violation");

    private final String code;
    private final String message;

    ErrorCode(String code, String message) {
        this.code = code;
        this.message = message;
    }
}
