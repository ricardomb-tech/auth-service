package com.auth_service.auth.domain.exception;

public class TargetAccountNotFoundException extends RuntimeException {

    public TargetAccountNotFoundException(String message) {
        super(message);
    }
}
