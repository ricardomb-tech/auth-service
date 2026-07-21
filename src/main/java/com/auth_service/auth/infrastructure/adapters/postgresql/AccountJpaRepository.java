package com.auth_service.auth.infrastructure.adapters.postgresql;

import com.auth_service.auth.domain.model.Role;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface AccountJpaRepository extends JpaRepository<AccountEntity, UUID> {

    Optional<AccountEntity> findByEmail(String email);

    boolean existsByRolesContaining(Role role);
}
