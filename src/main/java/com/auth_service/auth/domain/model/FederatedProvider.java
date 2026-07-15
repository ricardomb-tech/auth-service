package com.auth_service.auth.domain.model;

/** Proveedores de identidad federada soportados (Epic 2) — GITHUB se declara ahora aunque solo GOOGLE se implementa en esta historia (Story 2.2 lo reutiliza). */
public enum FederatedProvider {
    GOOGLE,
    GITHUB
}
