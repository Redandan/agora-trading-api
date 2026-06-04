package com.agora.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

/**
 * Gates /actuator/prometheus + /actuator/metrics behind MCP OPS/DEV key or localhost IP.
 *
 * <p>Health + info remain public (liveness / readiness probes). Prometheus scrape endpoint
 * MUST NOT be publicly exposed because metrics leak tag values (symbols, strategy IDs,
 * error counts, server internals) that help attackers profile the system.</p>
 *
 * <p>Accepted credentials:
 * <ul>
 *   <li>{@code Authorization: Bearer <MCP_OPS_KEY>} or {@code <MCP_API_KEY>}</li>
 *   <li>Request from {@code 127.0.0.1 / ::1} (local Prometheus via SSH tunnel)</li>
 * </ul>
 * The filter sits before Spring Security for /actuator/prometheus and /actuator/metrics,
 * which are otherwise permitAll in {@link SecurityPaths}. Reject returns 401.
 */
@Slf4j
@Component
@Order(-150)
public class ActuatorAuthFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final Set<String> LOCALHOST_ADDRS =
            Set.of("127.0.0.1", "::1", "0:0:0:0:0:0:0:1");

    private final String devKey;
    private final String opsKey;

    public ActuatorAuthFilter(
            @Value("${mcp.api-key:}") String devKey,
            @Value("${mcp.ops-key:}") String opsKey) {
        this.devKey = devKey;
        this.opsKey = opsKey;
    }

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        String uri = request.getRequestURI();
        return !uri.contains("/actuator/prometheus") && !uri.contains("/actuator/metrics");
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        String ip = request.getRemoteAddr();
        if (LOCALHOST_ADDRS.contains(ip)) {
            chain.doFilter(request, response);
            return;
        }

        String header = request.getHeader("Authorization");
        String token = null;
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            String val = header.substring(BEARER_PREFIX.length()).strip();
            if (!val.isEmpty()) token = val;
        }

        boolean authorized =
                (token != null && !devKey.isBlank() && devKey.equals(token))
             || (token != null && !opsKey.isBlank() && opsKey.equals(token));

        if (!authorized) {
            log.warn("[ActuatorAuth] DENIED path={} ip={}", request.getRequestURI(), ip);
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setHeader("WWW-Authenticate", "Bearer realm=\"actuator\"");
            return;
        }

        chain.doFilter(request, response);
    }
}
