package io.kyligence.ragagent.server.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.kyligence.ragagent.core.auth.Role;
import io.kyligence.ragagent.core.auth.WorkspaceService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(WorkspaceController.class)
@DisplayName("WorkspaceController Integration Tests")
class WorkspaceControllerTest {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private ObjectMapper objectMapper;

  @MockBean
  private WorkspaceService workspaceService;

  @Test
  @DisplayName("GET /api/v1/workspaces returns list of workspaces for authenticated user")
  void listWorkspaces_withAuthenticatedUser_returnsWorkspaces() throws Exception {
    String userId = "user-123";
    List<WorkspaceService.WorkspaceView> workspaces = List.of(
        new WorkspaceService.WorkspaceView("ws-1", "Workspace 1", userId, Role.OWNER),
        new WorkspaceService.WorkspaceView("ws-2", "Workspace 2", "other-user", Role.MEMBER));

    when(workspaceService.listForUser(userId)).thenReturn(workspaces);

    mockMvc.perform(get("/api/v1/workspaces")
            .with(authentication(createAuthentication(userId))))
        .andExpect(status().isOk())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data").isArray())
        .andExpect(jsonPath("$.data.length()").value(2))
        .andExpect(jsonPath("$.data[0].id").value("ws-1"))
        .andExpect(jsonPath("$.data[0].name").value("Workspace 1"))
        .andExpect(jsonPath("$.data[0].ownerId").value(userId))
        .andExpect(jsonPath("$.data[0].role").value("OWNER"))
        .andExpect(jsonPath("$.data[1].id").value("ws-2"))
        .andExpect(jsonPath("$.data[1].role").value("MEMBER"));
  }

  @Test
  @DisplayName("GET /api/v1/workspaces returns empty list when user has no workspaces")
  void listWorkspaces_withNoWorkspaces_returnsEmptyList() throws Exception {
    String userId = "user-456";

    when(workspaceService.listForUser(userId)).thenReturn(List.of());

    mockMvc.perform(get("/api/v1/workspaces")
            .with(authentication(createAuthentication(userId))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data").isArray())
        .andExpect(jsonPath("$.data.length()").value(0));
  }

  @Test
  @WithMockUser
  @DisplayName("GET /api/v1/workspaces requires authentication")
  void listWorkspaces_withoutAuthentication_returnsUnauthorized() throws Exception {
    mockMvc.perform(get("/api/v1/workspaces"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("POST /api/v1/workspaces creates new workspace")
  void createWorkspace_withValidRequest_createsWorkspace() throws Exception {
    String userId = "user-789";
    WorkspaceController.CreateRequest request = new WorkspaceController.CreateRequest(
        "New Workspace");

    WorkspaceService.WorkspaceView createdWorkspace = new WorkspaceService.WorkspaceView(
        "ws-new", "New Workspace", userId, Role.OWNER);

    when(workspaceService.create(userId, "New Workspace")).thenReturn(createdWorkspace);

    mockMvc.perform(post("/api/v1/workspaces")
            .with(authentication(createAuthentication(userId)))
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isOk())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.id").value("ws-new"))
        .andExpect(jsonPath("$.data.name").value("New Workspace"))
        .andExpect(jsonPath("$.data.ownerId").value(userId))
        .andExpect(jsonPath("$.data.role").value("OWNER"));
  }

  @Test
  @DisplayName("POST /api/v1/workspaces returns 400 for blank name")
  void createWorkspace_withBlankName_returnsBadRequest() throws Exception {
    String userId = "user-abc";
    WorkspaceController.CreateRequest request = new WorkspaceController.CreateRequest("");

    mockMvc.perform(post("/api/v1/workspaces")
            .with(authentication(createAuthentication(userId)))
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("POST /api/v1/workspaces returns 400 for name exceeding max length")
  void createWorkspace_withTooLongName_returnsBadRequest() throws Exception {
    String userId = "user-def";
    String longName = "A".repeat(129); // Max is 128
    WorkspaceController.CreateRequest request = new WorkspaceController.CreateRequest(longName);

    mockMvc.perform(post("/api/v1/workspaces")
            .with(authentication(createAuthentication(userId)))
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isBadRequest());
  }

  @Test
  @WithMockUser
  @DisplayName("POST /api/v1/workspaces requires authentication")
  void createWorkspace_withoutAuthentication_returnsUnauthorized() throws Exception {
    WorkspaceController.CreateRequest request = new WorkspaceController.CreateRequest(
        "Test Workspace");

    mockMvc.perform(post("/api/v1/workspaces")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isUnauthorized());
  }

  // Helper method to create authentication
  private Authentication createAuthentication(String userId) {
    return new Authentication() {
      @Override
      public String getName() {
        return userId;
      }

      @Override
      public Object getPrincipal() {
        return userId;
      }

      @Override
      public Object getCredentials() {
        return null;
      }

      @Override
      public Object getDetails() {
        return null;
      }

      @Override
      public java.util.Collection<? extends org.springframework.security.core.GrantedAuthority>
      getAuthorities() {
        return List.of();
      }

      @Override
      public boolean isAuthenticated() {
        return true;
      }

      @Override
      public void setAuthenticated(boolean isAuthenticated) {
      }
    };
  }
}
