package com.gameops.craft.domain;

public record CurrentUser(long userId, String username, String role, String token) {
    public boolean isOperator() {
        return "OPERATOR".equals(role);
    }
}
