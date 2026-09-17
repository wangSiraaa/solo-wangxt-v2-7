package com.gameops.craft.web;

import com.gameops.craft.domain.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;

public final class CurrentUsers {
    private CurrentUsers() {}

    public static CurrentUser from(HttpServletRequest request) {
        CurrentUser user = (CurrentUser) request.getAttribute(AuthInterceptor.ATTR_USER);
        if (user == null) {
            throw new IllegalStateException("no authenticated user on request");
        }
        return user;
    }
}
