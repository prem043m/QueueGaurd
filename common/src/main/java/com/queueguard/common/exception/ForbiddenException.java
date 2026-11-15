package com.queueguard.common.exception;

public class ForbiddenException extends BusinessException {
    public ForbiddenException(String message) {
        super(403, "FORBIDDEN", message);
    }
}
