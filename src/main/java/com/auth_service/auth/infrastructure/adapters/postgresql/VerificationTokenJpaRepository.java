package com.auth_service.auth.infrastructure.adapters.postgresql;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface VerificationTokenJpaRepository extends JpaRepository<VerificationTokenEntity, UUID> {
}
