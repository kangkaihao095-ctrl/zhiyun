package com.zhiyun.security;

public record AuthUser(long userId, long tenantId, String email, String displayName, boolean ops) {
    public AuthUser(long userId, long tenantId, String email, String displayName) {
        this(userId, tenantId, email, displayName, false);
    }
}
