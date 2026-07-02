package com.auth_service.auth.domain.port;

import com.auth_service.auth.domain.model.HashedPassword;
import com.auth_service.auth.domain.model.RawPassword;

public interface PasswordHasher {

    HashedPassword hash(RawPassword rawPassword);
}
