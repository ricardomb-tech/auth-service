package com.auth_service.auth.application.usecase.command;

/** Entrada cruda del controller — la validación de formato ocurre dentro del caso de uso. */
public record RegisterAccountCommand(String rawEmail, String rawPassword) {

    @Override
    public String toString() {
        return "RegisterAccountCommand[rawEmail=" + rawEmail + ", rawPassword=REDACTED]";
    }
}
