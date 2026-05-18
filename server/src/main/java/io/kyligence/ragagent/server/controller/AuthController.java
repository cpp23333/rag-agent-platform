package io.kyligence.ragagent.server.controller;

import io.kyligence.ragagent.core.auth.AuthService;
import io.kyligence.ragagent.shared.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证相关接口
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    public record RegisterRequest(
        @Email @NotBlank String email,
        @NotBlank @Size(min = 8, max = 128) String password,
        @Size(max = 128) String fullName) {}

    public record LoginRequest(@Email @NotBlank String email, @NotBlank String password) {}

    public record RefreshRequest(@NotBlank String refreshToken) {}

    private final AuthService auth;

    public AuthController(AuthService auth) {
        this.auth = auth;
    }

    @PostMapping("/register")
    public ApiResponse<AuthService.TokenPair> register(@Valid @RequestBody RegisterRequest req) {
        return ApiResponse.ok(auth.register(
            new AuthService.RegisterCommand(req.email(), req.password(), req.fullName())));
    }

    @PostMapping("/login")
    public ApiResponse<AuthService.TokenPair> login(@Valid @RequestBody LoginRequest req) {
        return ApiResponse.ok(auth.login(
            new AuthService.LoginCommand(req.email(), req.password())));
    }

    @PostMapping("/refresh")
    public ApiResponse<AuthService.TokenPair> refresh(@Valid @RequestBody RefreshRequest req) {
        return ApiResponse.ok(auth.refresh(req.refreshToken()));
    }
}
