package io.kyligence.ragagent.shared.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/**
 * 统一 API 响应包络
 *
 * @param <T> 响应数据类型
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse<T> {
    private boolean success;
    private T data;
    private String errorCode;
    private String errorMessage;

    private ApiResponse(boolean success, T data, String errorCode, String errorMessage) {
        this.success = success;
        this.data = data;
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
    }

    /**
     * 成功响应
     */
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(true, data, null, null);
    }

    /**
     * 成功响应（无数据）
     */
    public static <T> ApiResponse<T> success() {
        return new ApiResponse<>(true, null, null, null);
    }

    /**
     * 成功响应（别名）
     */
    public static <T> ApiResponse<T> ok(T data) {
        return success(data);
    }

    /**
     * 成功响应（无数据，别名）
     */
    public static <T> ApiResponse<T> ok() {
        return success();
    }

    /**
     * 失败响应
     */
    public static <T> ApiResponse<T> error(String errorCode, String errorMessage) {
        return new ApiResponse<>(false, null, errorCode, errorMessage);
    }
}
