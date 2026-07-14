package com.auth_service.auth.config;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class NoOpTestController {

    @GetMapping("/__test/protected")
    public String protectedEndpoint() {
        return "ok";
    }
}
