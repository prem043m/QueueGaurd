package com.queueguard.common.exception;

/** Base business exception carrying an HTTP status and machine-readable code. */
public class BusinessException extends RuntimeException {

    private final int status;
    private final String code;

    public BusinessException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}
