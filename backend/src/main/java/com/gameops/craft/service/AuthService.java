package com.gameops.craft.service;

import com.gameops.craft.common.ApiException;
import com.gameops.craft.common.DocNumbers;
import com.gameops.craft.repo.UserRepository;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository users;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    private final Clock clock;
    private final long tokenTtlHours;

    public AuthService(UserRepository users, Clock clock,
                       @Value("${app.token-ttl-hours:12}") long tokenTtlHours) {
        this.users = users;
        this.clock = clock;
        this.tokenTtlHours = tokenTtlHours;
    }

    public record LoginResult(String token, long userId, String username, String displayName,
                              String role, Instant expiresAt) {}

    public LoginResult login(String username, String password) {
        UserRepository.UserRow user = users.findByUsername(username)
                .orElseThrow(() -> ApiException.unauthorized("invalid username or password"));
        if (!encoder.matches(password, user.passwordHash())) {
            throw ApiException.unauthorized("invalid username or password");
        }
        byte[] raw = new byte[36];
        RANDOM.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        Instant now = Instant.now(clock);
        Instant expiresAt = now.plus(Duration.ofHours(tokenTtlHours));
        users.insertToken(token, user.id(), user.role(), now, expiresAt);
        return new LoginResult(token, user.id(), user.username(), user.displayName(), user.role(), expiresAt);
    }
}
