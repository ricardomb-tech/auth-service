package com.auth_service.auth.domain.model;

/** Máquina de estados de la Cuenta — transiciones solo desde application/usecase (AD-6, AD-13). */
public enum AccountStatus {
    PENDING_VERIFICATION,
    ACTIVE,
    LOCKED,
    DISABLED
}
