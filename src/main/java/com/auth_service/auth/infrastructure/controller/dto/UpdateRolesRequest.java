package com.auth_service.auth.infrastructure.controller.dto;

import java.util.Set;

public record UpdateRolesRequest(Set<String> roles) {
}
