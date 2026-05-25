package io.kyligence.ragagent.server.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import io.kyligence.ragagent.core.model.ModelProvider;
import io.kyligence.ragagent.core.model.ModelProviderService;
import io.kyligence.ragagent.shared.api.ApiResponse;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ModelProviderControllerTest {

    @Mock private ModelProviderService service;
    private ModelProviderController controller;

    @BeforeEach
    void setUp() {
        controller = new ModelProviderController(service);
    }

    @Test
    void createDoesNotExposeEncryptedApiKey() {
        ModelProvider provider = new ModelProvider();
        provider.setId("test-id");
        provider.setName("openai");
        provider.setType("openai");
        provider.setBaseUrl("https://api.openai.com/v1");
        provider.setApiKeyEncrypted("encrypted-key-should-not-be-exposed");
        provider.setModelsJson("{\"chat\":[\"gpt-4o\"]}");
        provider.setEnabled(true);
        provider.setCreatedAt(LocalDateTime.now());
        provider.setUpdatedAt(LocalDateTime.now());

        when(service.create(eq(1L), any())).thenReturn(provider);

        var req =
                new ModelProviderController.CreateRequest(
                        "openai",
                        "openai",
                        "https://api.openai.com/v1",
                        "sk-test",
                        "{\"chat\":[\"gpt-4o\"]}");

        ApiResponse<?> response = controller.create(1L, req);

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getData()).isNotNull();
        // Verify the response doesn't contain apiKeyEncrypted field
        String responseStr = response.getData().toString();
        assertThat(responseStr).doesNotContain("encrypted-key-should-not-be-exposed");
        assertThat(responseStr).doesNotContain("apiKeyEncrypted");
    }

    @Test
    void listDoesNotExposeEncryptedApiKeys() {
        ModelProvider provider = new ModelProvider();
        provider.setId("test-id");
        provider.setName("openai");
        provider.setApiKeyEncrypted("encrypted-key-should-not-be-exposed");

        when(service.list(1L)).thenReturn(List.of(provider));

        ApiResponse<?> response = controller.list(1L);

        assertThat(response.isSuccess()).isTrue();
        String responseStr = response.getData().toString();
        assertThat(responseStr).doesNotContain("encrypted-key-should-not-be-exposed");
        assertThat(responseStr).doesNotContain("apiKeyEncrypted");
    }
}
