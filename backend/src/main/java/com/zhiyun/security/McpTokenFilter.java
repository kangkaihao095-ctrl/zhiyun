package com.zhiyun.security;

import com.zhiyun.config.ZhiyunProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * MCP 走 {@code X-Zhiyun-Mcp-Token}，不裸 permitAll。
 */
public class McpTokenFilter extends OncePerRequestFilter {
    public static final String HEADER = "X-Zhiyun-Mcp-Token";

    private final ZhiyunProperties properties;
    private final Environment environment;

    public McpTokenFilter(ZhiyunProperties properties, Environment environment) {
        this.properties = properties;
        this.environment = environment;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI() == null ? "" : request.getRequestURI();
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            return true;
        }
        return !path.startsWith("/api/mcp");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String[] profiles = environment.getActiveProfiles();
        if (!properties.mcpEnabled(profiles)) {
            unauthorized(response, "MCP disabled without ZHIYUN_MCP_TOKEN");
            return;
        }
        String expected = properties.resolvedMcpToken(profiles);
        String actual = request.getHeader(HEADER);
        if (!tokenEquals(expected, actual)) {
            unauthorized(response, "invalid MCP token");
            return;
        }
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken("mcp", null, List.of());
        SecurityContextHolder.getContext().setAuthentication(auth);
        filterChain.doFilter(request, response);
    }

    private static boolean tokenEquals(String expected, String actual) {
        if (expected == null || actual == null || expected.isBlank()) {
            return false;
        }
        byte[] left = expected.getBytes(StandardCharsets.UTF_8);
        byte[] right = actual.getBytes(StandardCharsets.UTF_8);
        if (left.length != right.length) {
            MessageDigest.isEqual(left, left);
            return false;
        }
        return MessageDigest.isEqual(left, right);
    }

    private static void unauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"error\":\"" + message + "\",\"status\":401}");
    }
}
