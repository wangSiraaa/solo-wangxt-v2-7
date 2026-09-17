package com.gameops.craft.web;

import com.gameops.craft.common.ApiException;
import com.gameops.craft.domain.CurrentUser;
import com.gameops.craft.repo.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class AuthInterceptor implements HandlerInterceptor {

    public static final String ATTR_USER = "currentUser";
    private static final String HEADER = "X-Auth-Token";

    private final UserRepository users;
    private final Clock clock;

    public AuthInterceptor(UserRepository users, Clock clock) {
        this.users = users;
        this.clock = clock;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String token = request.getHeader(HEADER);
        if (token == null || token.isBlank()) {
            throw ApiException.unauthorized("missing " + HEADER);
        }
        UserRepository.TokenRow row = users.findValidToken(token, Instant.now(clock))
                .orElseThrow(() -> ApiException.unauthorized("invalid or expired token"));

        String path = request.getRequestURI();
        boolean operatorPath = path.startsWith("/api/operator/");
        if (operatorPath && !"OPERATOR".equals(row.role())) {
            throw ApiException.forbidden("operator role required");
        }
        if (!operatorPath && path.startsWith("/api/player/") && !"PLAYER".equals(row.role())) {
            throw ApiException.forbidden("player role required");
        }
        request.setAttribute(ATTR_USER, new CurrentUser(row.userId(), null, row.role(), token));
        return true;
    }
}
