package io.kyligence.ragagent.server.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.kyligence.ragagent.server.controller.AuthController;
import io.kyligence.ragagent.server.controller.WorkspaceController;
import io.kyligence.ragagent.shared.api.ApiResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@DisplayName("Foundation E2E Integration Test")
class FoundationIntegrationTest {

  @Container
  static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
      .withDatabaseName("testdb")
      .withUsername("testuser")
      .withPassword("testpass");

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private ObjectMapper objectMapper;

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", mysql::getJdbcUrl);
    registry.add("spring.datasource.username", mysql::getUsername);
    registry.add("spring.datasource.password", mysql::getPassword);
    registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");

    // JWT configuration for tests
    registry.add("app.jwt.access-token-secret",
        () -> "test-access-secret-key-minimum-32-bytes-required");
    registry.add("app.jwt.refresh-token-secret",
        () -> "test-refresh-secret-key-minimum-32-bytes-required");
    registry.add("app.jwt.access-token-validity-minutes", () -> 60);
    registry.add("app.jwt.refresh-token-validity-days", () -> 30);
  }

  @BeforeAll
  static void beforeAll() {
    mysql.start();
  }

  @Test
  @DisplayName("Complete E2E flow: register -> login -> list workspaces -> refresh token")
  void completeFoundationFlow() throws Exception {
    // 1. Register a new user
    AuthController.RegisterRequest registerReq = new AuthController.RegisterRequest(
        "e2e-test@example.com",
        "SecurePassword123",
        "E2E Test User");

    MvcResult registerResult = mockMvc.perform(post("/api/v1/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(registerReq)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.accessToken").exists())
        .andExpect(jsonPath("$.data.refreshToken").exists())
        .andReturn();

    String registerResponse = registerResult.getResponse().getContentAsString();
    Map<String, Object> registerData = objectMapper.readValue(registerResponse, Map.class);
    Map<String, String> registerTokens = (Map<String, String>) registerData.get("data");
    String accessToken1 = registerTokens.get("accessToken");
    String refreshToken1 = registerTokens.get("refreshToken");

    assertThat(accessToken1).isNotNull().isNotEmpty();
    assertThat(refreshToken1).isNotNull().isNotEmpty();

    // 2. Login with the same credentials
    AuthController.LoginRequest loginReq = new AuthController.LoginRequest(
        "e2e-test@example.com",
        "SecurePassword123");

    MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(loginReq)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.accessToken").exists())
        .andExpect(jsonPath("$.data.refreshToken").exists())
        .andReturn();

    String loginResponse = loginResult.getResponse().getContentAsString();
    Map<String, Object> loginData = objectMapper.readValue(loginResponse, Map.class);
    Map<String, String> loginTokens = (Map<String, String>) loginData.get("data");
    String accessToken2 = loginTokens.get("accessToken");
    String refreshToken2 = loginTokens.get("refreshToken");

    assertThat(accessToken2).isNotNull().isNotEmpty();
    assertThat(refreshToken2).isNotNull().isNotEmpty();

    // 3. List workspaces using the access token
    mockMvc.perform(get("/api/v1/workspaces")
            .header("Authorization", "Bearer " + accessToken2))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data").isArray())
        .andExpect(jsonPath("$.data.length()").value(1))  // Default workspace created
        .andExpect(jsonPath("$.data[0].name").value("E2E Test User's Workspace"))
        .andExpect(jsonPath("$.data[0].role").value("OWNER"));

    // 4. Create a new workspace
    WorkspaceController.CreateRequest createWsReq = new WorkspaceController.CreateRequest(
        "Second Workspace");

    mockMvc.perform(post("/api/v1/workspaces")
            .header("Authorization", "Bearer " + accessToken2)
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(createWsReq)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.name").value("Second Workspace"))
        .andExpect(jsonPath("$.data.role").value("OWNER"));

    // 5. Verify both workspaces are now listed
    mockMvc.perform(get("/api/v1/workspaces")
            .header("Authorization", "Bearer " + accessToken2))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data").isArray())
        .andExpect(jsonPath("$.data.length()").value(2));

    // 6. Refresh the token
    AuthController.RefreshRequest refreshReq = new AuthController.RefreshRequest(refreshToken2);

    MvcResult refreshResult = mockMvc.perform(post("/api/v1/auth/refresh")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(refreshReq)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.accessToken").exists())
        .andExpect(jsonPath("$.data.refreshToken").exists())
        .andReturn();

    String refreshResponse = refreshResult.getResponse().getContentAsString();
    Map<String, Object> refreshData = objectMapper.readValue(refreshResponse, Map.class);
    Map<String, String> newTokens = (Map<String, String>) refreshData.get("data");
    String accessToken3 = newTokens.get("accessToken");
    String refreshToken3 = newTokens.get("refreshToken");

    assertThat(accessToken3).isNotNull().isNotEmpty();
    assertThat(refreshToken3).isNotNull().isNotEmpty();

    // 7. Use the new access token to list workspaces again
    mockMvc.perform(get("/api/v1/workspaces")
            .header("Authorization", "Bearer " + accessToken3))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.length()").value(2));
  }

  @Test
  @DisplayName("Workspace isolation: users can only see their own workspaces")
  void workspaceIsolation() throws Exception {
    // Create first user
    AuthController.RegisterRequest user1Req = new AuthController.RegisterRequest(
        "user1-isolation@example.com",
        "Password123",
        "User One");

    MvcResult user1Register = mockMvc.perform(post("/api/v1/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(user1Req)))
        .andExpect(status().isOk())
        .andReturn();

    Map<String, Object> user1Data = objectMapper.readValue(
        user1Register.getResponse().getContentAsString(), Map.class);
    String user1Token = ((Map<String, String>) user1Data.get("data")).get("accessToken");

    // Create second user
    AuthController.RegisterRequest user2Req = new AuthController.RegisterRequest(
        "user2-isolation@example.com",
        "Password456",
        "User Two");

    MvcResult user2Register = mockMvc.perform(post("/api/v1/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(user2Req)))
        .andExpect(status().isOk())
        .andReturn();

    Map<String, Object> user2Data = objectMapper.readValue(
        user2Register.getResponse().getContentAsString(), Map.class);
    String user2Token = ((Map<String, String>) user2Data.get("data")).get("accessToken");

    // User 1 lists workspaces - should only see their own
    MvcResult user1Workspaces = mockMvc.perform(get("/api/v1/workspaces")
            .header("Authorization", "Bearer " + user1Token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(1))
        .andExpect(jsonPath("$.data[0].name").value("User One's Workspace"))
        .andReturn();

    // User 2 lists workspaces - should only see their own
    MvcResult user2Workspaces = mockMvc.perform(get("/api/v1/workspaces")
            .header("Authorization", "Bearer " + user2Token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(1))
        .andExpect(jsonPath("$.data[0].name").value("User Two's Workspace"))
        .andReturn();

    // Verify workspaces are different
    String user1WsResponse = user1Workspaces.getResponse().getContentAsString();
    String user2WsResponse = user2Workspaces.getResponse().getContentAsString();
    assertThat(user1WsResponse).isNotEqualTo(user2WsResponse);
  }

  @Test
  @DisplayName("Authentication: access to protected endpoints requires valid JWT")
  void authenticationRequired() throws Exception {
    // Try to access protected endpoint without token
    mockMvc.perform(get("/api/v1/workspaces"))
        .andExpect(status().isUnauthorized());

    // Try with invalid token
    mockMvc.perform(get("/api/v1/workspaces")
            .header("Authorization", "Bearer invalid-token"))
        .andExpect(status().isUnauthorized());

    // Register and get valid token
    AuthController.RegisterRequest registerReq = new AuthController.RegisterRequest(
        "auth-test@example.com",
        "Password789",
        "Auth Test");

    MvcResult registerResult = mockMvc.perform(post("/api/v1/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(registerReq)))
        .andExpect(status().isOk())
        .andReturn();

    Map<String, Object> data = objectMapper.readValue(
        registerResult.getResponse().getContentAsString(), Map.class);
    String validToken = ((Map<String, String>) data.get("data")).get("accessToken");

    // Access with valid token should succeed
    mockMvc.perform(get("/api/v1/workspaces")
            .header("Authorization", "Bearer " + validToken))
        .andExpect(status().isOk());
  }

  @Test
  @DisplayName("Health endpoint is publicly accessible")
  void healthEndpointPublic() throws Exception {
    mockMvc.perform(get("/health"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.status").value("UP"));
  }

  @Test
  @DisplayName("Duplicate registration fails with appropriate error")
  void duplicateRegistrationFails() throws Exception {
    String email = "duplicate@example.com";

    AuthController.RegisterRequest firstReg = new AuthController.RegisterRequest(
        email,
        "Password123",
        "First User");

    // First registration succeeds
    mockMvc.perform(post("/api/v1/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(firstReg)))
        .andExpect(status().isOk());

    AuthController.RegisterRequest secondReg = new AuthController.RegisterRequest(
        email,
        "DifferentPassword456",
        "Second User");

    // Second registration with same email fails
    mockMvc.perform(post("/api/v1/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(secondReg)))
        .andExpect(status().is4xxClientError());
  }

  @Test
  @DisplayName("Login with wrong password fails")
  void loginWithWrongPasswordFails() throws Exception {
    // Register user
    AuthController.RegisterRequest registerReq = new AuthController.RegisterRequest(
        "wrong-pass@example.com",
        "CorrectPassword123",
        "Test User");

    mockMvc.perform(post("/api/v1/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(registerReq)))
        .andExpect(status().isOk());

    // Try to login with wrong password
    AuthController.LoginRequest loginReq = new AuthController.LoginRequest(
        "wrong-pass@example.com",
        "WrongPassword456");

    mockMvc.perform(post("/api/v1/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(loginReq)))
        .andExpect(status().is4xxClientError());
  }
}
