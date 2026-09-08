package com.zhiyun.security;

public record AuthUser(long userId, long tenantId, String email, String displayName) {
}
