package com.auth_service.auth.domain.exception;

public class SelfManagementNotAllowedException extends RuntimeException {

    public SelfManagementNotAllowedException(String message) {
        super(message);
    }
}
