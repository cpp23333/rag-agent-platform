package io.kyligence.ragagent.server.config;

import io.kyligence.ragagent.core.auth.ApiKeyService;
import io.kyligence.ragagent.core.auth.Role;
import io.kyligence.ragagent.core.tenant.WorkspaceContext;
import io.kyligence.ragagent.core.tenant.WorkspaceContextHolder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * API Key 认证过滤器
 * 从 X-Api-Key header 中提取并验证 API Key
 */
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    private final ApiKeyService apiKeyService;

    public ApiKeyAuthenticationFilter(ApiKeyService apiKeyService) {
        this.apiKeyService = apiKeyService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
            throws ServletException, IOException {
        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            String header = req.getHeader("X-Api-Key");
            if (header != null) {
                apiKeyService.verify(header).ifPresent(resolved -> {
                    UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                        resolved.apiKey().getId(), null, List.of(new SimpleGrantedAuthority("ROLE_API")));
                    SecurityContextHolder.getContext().setAuthentication(auth);
                    WorkspaceContextHolder.set(new WorkspaceContext(
                        resolved.workspaceId(), resolved.apiKey().getId(), Role.MEMBER));
                });
            }
        }
        chain.doFilter(req, resp);
    }
}
