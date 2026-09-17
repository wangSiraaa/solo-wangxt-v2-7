package com.gameops.craft.web;

import com.gameops.craft.service.AuthService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

public class AuthDtos {

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {}

    public record LoginResponse(String token, long userId, String username, String displayName,
                                String role, java.time.Instant expiresAt) {
        static LoginResponse from(AuthService.LoginResult r) {
            return new LoginResponse(r.token(), r.userId(), r.username(), r.displayName(), r.role(), r.expiresAt());
        }
    }
}
