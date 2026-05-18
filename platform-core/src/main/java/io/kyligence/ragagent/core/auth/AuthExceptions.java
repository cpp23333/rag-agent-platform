package io.kyligence.ragagent.core.auth;

import io.kyligence.ragagent.shared.exception.ErrorCode;
import io.kyligence.ragagent.shared.exception.PlatformException;

/**
 * 认证相关异常
 */
public class AuthExceptions {

    public static class InvalidCredentialsException extends PlatformException {
        public InvalidCredentialsException() {
            super(ErrorCode.INVALID_CREDENTIALS);
        }
    }

    public static class UserNotFoundException extends PlatformException {
        public UserNotFoundException() {
            super(ErrorCode.USER_NOT_FOUND);
        }

        public UserNotFoundException(String userId) {
            super(ErrorCode.USER_NOT_FOUND, "User not found: " + userId);
        }
    }

    public static class UserAlreadyExistsException extends PlatformException {
        public UserAlreadyExistsException(String email) {
            super(ErrorCode.USER_ALREADY_EXISTS, "User already exists: " + email);
        }
    }

    public static class UnauthorizedException extends PlatformException {
        public UnauthorizedException() {
            super(ErrorCode.UNAUTHORIZED);
        }

        public UnauthorizedException(String message) {
            super(ErrorCode.UNAUTHORIZED, message);
        }
    }
}
