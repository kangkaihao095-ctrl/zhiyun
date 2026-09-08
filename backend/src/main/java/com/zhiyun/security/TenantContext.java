package com.zhiyun.security;

import com.zhiyun.common.ApiException;

public final class TenantContext {
    private static final ThreadLocal<AuthUser> HOLDER = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void set(AuthUser user) {
        HOLDER.set(user);
    }

    public static AuthUser get() {
        return HOLDER.get();
    }

    public static AuthUser require() {
        AuthUser user = HOLDER.get();
        if (user == null) {
            throw ApiException.unauthorized("missing login context");
        }
        return user;
    }

    public static long tenantId() {
        return require().tenantId();
    }

    public static long userId() {
        return require().userId();
    }

    public static void clear() {
        HOLDER.remove();
    }
}
