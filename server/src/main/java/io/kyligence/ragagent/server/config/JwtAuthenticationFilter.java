package io.kyligence.ragagent.server.config;

import io.kyligence.ragagent.core.auth.JwtTokenProvider;
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
 * JWT 认证过滤器
 * 从 Authorization: Bearer <token> header 中提取并验证 JWT
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenProvider jwt;

    public JwtAuthenticationFilter(JwtTokenProvider jwt) {
        this.jwt = jwt;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
            throws ServletException, IOException {
        String header = req.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7);
            try {
                var claims = jwt.validateAccessToken(token);
                String userId = jwt.getUserId(claims);

                UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                    userId, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
                SecurityContextHolder.getContext().setAuthentication(auth);

                // Workspace ID 从 X-Workspace-Id header 指定
                String wsHeader = req.getHeader("X-Workspace-Id");
                if (wsHeader != null) {
                    String workspaceId = jwt.getWorkspaceId(claims);
                    Role role = jwt.getRole(claims);
                    WorkspaceContextHolder.setContext(new WorkspaceContext(
                        workspaceId, userId, role));
                }
            } catch (Exception ignored) {
                // Fall through unauthenticated
            }
        }
        try {
            chain.doFilter(req, resp);
        } finally {
            WorkspaceContextHolder.clear();
            SecurityContextHolder.clearContext();
        }
    }
}
