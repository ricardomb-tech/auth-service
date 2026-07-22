package com.auth_service.auth.infrastructure.controller.dto;

import java.util.List;

public record PagedAccountsResponse(List<AdminAccountResponse> content, int page, int size, long totalElements) {
}
