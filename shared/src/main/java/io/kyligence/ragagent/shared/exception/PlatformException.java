package io.kyligence.ragagent.shared.exception;

import lombok.Getter;

/**
 * 平台业务异常基类
 */
@Getter
public class PlatformException extends RuntimeException {
    private final ErrorCode errorCode;

    public PlatformException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public PlatformException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public PlatformException(ErrorCode errorCode, Throwable cause) {
        super(errorCode.getMessage(), cause);
        this.errorCode = errorCode;
    }

    public PlatformException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public String getCode() {
        return errorCode.getCode();
    }
}
