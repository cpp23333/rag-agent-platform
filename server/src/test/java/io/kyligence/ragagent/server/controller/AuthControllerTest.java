package io.kyligence.ragagent.server.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.kyligence.ragagent.core.auth.AuthExceptions;
import io.kyligence.ragagent.core.auth.AuthService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AuthController.class)
@DisplayName("AuthController Integration Tests")
class AuthControllerTest {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private ObjectMapper objectMapper;

  @MockBean
  private AuthService authService;

  @Test
  @DisplayName("POST /api/v1/auth/register returns tokens for valid request")
  void register_withValidRequest_returnsTokens() throws Exception {
    AuthController.RegisterRequest request = new AuthController.RegisterRequest(
        "alice@example.com",
        "password123",
        "Alice Smith");

    AuthService.AuthTokens tokens = new AuthService.AuthTokens(
        "access-token-xyz",
        "refresh-token-abc");

    when(authService.register(
        eq("alice@example.com"),
        eq("password123"),
        eq("Alice Smith")))
        .thenReturn(tokens);

    mockMvc.perform(post("/api/v1/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isOk())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.accessToken").value("access-token-xyz"))
        .andExpect(jsonPath("$.data.refreshToken").value("refresh-token-abc"))
        .andExpect(jsonPath("$.errorCode").doesNotExist())
        .andExpect(jsonPath("$.errorMessage").doesNotExist());
  }

  @Test
  @DisplayName("POST /api/v1/auth/register returns 400 for invalid email")
  void register_withInvalidEmail_returnsBadRequest() throws Exception {
    AuthController.RegisterRequest request = new AuthController.RegisterRequest(
        "not-an-email",
        "password123",
        "Alice");

    mockMvc.perform(post("/api/v1/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("POST /api/v1/auth/register returns 400 for short password")
  void register_withShortPassword_returnsBadRequest() throws Exception {
    AuthController.RegisterRequest request = new AuthController.RegisterRequest(
        "alice@example.com",
        "short",
        "Alice");

    mockMvc.perform(post("/api/v1/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("POST /api/v1/auth/register handles UserAlreadyExistsException")
  void register_whenUserExists_handlesException() throws Exception {
    AuthController.RegisterRequest request = new AuthController.RegisterRequest(
        "existing@example.com",
        "password123",
        "Existing User");

    when(authService.register(anyString(), anyString(), anyString()))
        .thenThrow(new AuthExceptions.UserAlreadyExistsException("existing@example.com"));

    mockMvc.perform(post("/api/v1/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().is4xxClientError());
  }

  @Test
  @DisplayName("POST /api/v1/auth/login returns tokens for valid credentials")
  void login_withValidCredentials_returnsTokens() throws Exception {
    AuthController.LoginRequest request = new AuthController.LoginRequest(
        "bob@example.com",
        "password456");

    AuthService.AuthTokens tokens = new AuthService.AuthTokens(
        "access-token-123",
        "refresh-token-456");

    when(authService.login(
        eq("bob@example.com"),
        eq("password456"),
        isNull()))
        .thenReturn(tokens);

    mockMvc.perform(post("/api/v1/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isOk())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.accessToken").value("access-token-123"))
        .andExpect(jsonPath("$.data.refreshToken").value("refresh-token-456"));
  }

  @Test
  @DisplayName("POST /api/v1/auth/login returns 400 for invalid email")
  void login_withInvalidEmail_returnsBadRequest() throws Exception {
    AuthController.LoginRequest request = new AuthController.LoginRequest(
        "not-an-email",
        "password");

    mockMvc.perform(post("/api/v1/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("POST /api/v1/auth/login handles InvalidCredentialsException")
  void login_withWrongPassword_handlesException() throws Exception {
    AuthController.LoginRequest request = new AuthController.LoginRequest(
        "charlie@example.com",
        "wrong-password");

    when(authService.login(anyString(), anyString(), isNull()))
        .thenThrow(new AuthExceptions.InvalidCredentialsException());

    mockMvc.perform(post("/api/v1/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().is4xxClientError());
  }

  @Test
  @DisplayName("POST /api/v1/auth/login handles UnauthorizedException")
  void login_whenNotMemberOfWorkspace_handlesException() throws Exception {
    AuthController.LoginRequest request = new AuthController.LoginRequest(
        "dave@example.com",
        "password");

    when(authService.login(anyString(), anyString(), isNull()))
        .thenThrow(new AuthExceptions.UnauthorizedException("Not a member"));

    mockMvc.perform(post("/api/v1/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().is4xxClientError());
  }

  @Test
  @DisplayName("POST /api/v1/auth/refresh returns new tokens for valid refresh token")
  void refresh_withValidToken_returnsNewTokens() throws Exception {
    AuthController.RefreshRequest request = new AuthController.RefreshRequest(
        "valid-refresh-token");

    AuthService.AuthTokens newTokens = new AuthService.AuthTokens(
        "new-access-token",
        "new-refresh-token");

    when(authService.refresh("valid-refresh-token"))
        .thenReturn(newTokens);

    mockMvc.perform(post("/api/v1/auth/refresh")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isOk())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.accessToken").value("new-access-token"))
        .andExpect(jsonPath("$.data.refreshToken").value("new-refresh-token"));
  }

  @Test
  @DisplayName("POST /api/v1/auth/refresh returns 400 for blank token")
  void refresh_withBlankToken_returnsBadRequest() throws Exception {
    AuthController.RefreshRequest request = new AuthController.RefreshRequest("");

    mockMvc.perform(post("/api/v1/auth/refresh")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("POST /api/v1/auth/refresh handles UserNotFoundException")
  void refresh_whenUserNotFound_handlesException() throws Exception {
    AuthController.RefreshRequest request = new AuthController.RefreshRequest(
        "token-for-deleted-user");

    when(authService.refresh(anyString()))
        .thenThrow(new AuthExceptions.UserNotFoundException("user-123"));

    mockMvc.perform(post("/api/v1/auth/refresh")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().is4xxClientError());
  }

  @Test
  @DisplayName("POST /api/v1/auth/refresh handles UnauthorizedException")
  void refresh_whenUserHasNoWorkspaces_handlesException() throws Exception {
    AuthController.RefreshRequest request = new AuthController.RefreshRequest(
        "valid-token");

    when(authService.refresh(anyString()))
        .thenThrow(new AuthExceptions.UnauthorizedException("No workspaces"));

    mockMvc.perform(post("/api/v1/auth/refresh")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().is4xxClientError());
  }
}
