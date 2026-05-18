package io.kyligence.ragagent.server.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(HealthController.class)
@DisplayName("HealthController Integration Tests")
class HealthControllerTest {

  @Autowired
  private MockMvc mockMvc;

  @Test
  @DisplayName("GET /health returns UP status")
  void health_returnsUpStatus() throws Exception {
    mockMvc.perform(get("/health"))
        .andExpect(status().isOk())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.status").value("UP"))
        .andExpect(jsonPath("$.errorCode").doesNotExist())
        .andExpect(jsonPath("$.errorMessage").doesNotExist());
  }

  @Test
  @DisplayName("GET /health does not require authentication")
  void health_doesNotRequireAuthentication() throws Exception {
    // Health endpoint should be accessible without authentication
    mockMvc.perform(get("/health"))
        .andExpect(status().isOk());
  }

  @Test
  @DisplayName("GET /health has correct response structure")
  void health_hasCorrectResponseStructure() throws Exception {
    mockMvc.perform(get("/health"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").exists())
        .andExpect(jsonPath("$.data").exists())
        .andExpect(jsonPath("$.data.status").exists());
  }
}
