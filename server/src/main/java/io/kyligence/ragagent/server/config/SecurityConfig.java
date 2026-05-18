package io.kyligence.ragagent.server.config;

import io.kyligence.ragagent.core.auth.ApiKeyService;
import io.kyligence.ragagent.core.auth.JwtTokenProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Spring Security 配置
 * 配置双通道认证：JWT (Bearer token) 和 API Key (X-Api-Key header)
 */
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   JwtTokenProvider jwt,
                                                   ApiKeyService apiKeyService) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/health", "/api/v1/auth/**").permitAll()
                .anyRequest().authenticated())
            .addFilterBefore(new JwtAuthenticationFilter(jwt),
                UsernamePasswordAuthenticationFilter.class)
            .addFilterAfter(new ApiKeyAuthenticationFilter(apiKeyService),
                JwtAuthenticationFilter.class);
        return http.build();
    }
}
