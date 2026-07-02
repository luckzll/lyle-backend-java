package com.example.aiapp.utils;

import com.example.aiapp.security.CurrentUser;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

public final class CurrentUserUtils {

    private CurrentUserUtils() {
    }

    public static Long currentUserId() {
        return currentUser().id();
    }

    public static CurrentUser currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof CurrentUser currentUser) {
            return currentUser;
        }
        throw new IllegalStateException("Current user is not authenticated.");
    }
}
