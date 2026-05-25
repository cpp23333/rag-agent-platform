package io.kyligence.ragagent.core.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import io.kyligence.ragagent.core.tracing.TracingService;
import io.kyligence.ragagent.shared.exception.PlatformException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ModelGatewayImplTest {

    @Mock private ModelProviderService providerService;
    @Mock private ProviderClient client;
    @Mock private TracingService tracingService;

    private ModelGatewayImpl gateway;

    @BeforeEach
    void setUp() {
        ModelGatewayProperties props = new ModelGatewayProperties();
        props.setDefaultChat("openai/gpt-4o-mini");
        props.setDefaultEmbedding("openai/text-embedding-3-small");
        props.setRetryMaxAttempts(1);
        gateway = new ModelGatewayImpl(providerService, client, props, tracingService);
    }

    @Test
    void chatResolvesProviderAndCallsClient() {
        ModelProvider provider = new ModelProvider();
        provider.setBaseUrl("https://api.openai.com/v1");
        provider.setApiKeyEncrypted("enc");
        provider.setEnabled(true);
        when(providerService.getByName("openai")).thenReturn(provider);
        when(providerService.decryptApiKey("enc")).thenReturn("sk-test");

        ChatResponse expected =
                new ChatResponse(
                        "id",
                        "gpt-4o-mini",
                        "hello",
                        "stop",
                        null,
                        new ChatResponse.Usage(10, 5, 15));
        when(client.chat(eq("https://api.openai.com/v1"), eq("sk-test"), any()))
                .thenReturn(expected);

        ChatRequest req =
                new ChatRequest("openai/gpt-4o-mini", List.of(ChatMessage.user("hi")));
        ChatResponse resp = gateway.chat(req);

        assertThat(resp.content()).isEqualTo("hello");
        verify(client).chat(any(), any(), any());
    }

    @Test
    void chatUsesDefaultModelWhenNull() {
        ModelProvider provider = new ModelProvider();
        provider.setBaseUrl("https://api.openai.com/v1");
        provider.setApiKeyEncrypted("enc");
        provider.setEnabled(true);
        when(providerService.getByName("openai")).thenReturn(provider);
        when(providerService.decryptApiKey("enc")).thenReturn("sk-test");
        when(client.chat(any(), any(), any()))
                .thenReturn(
                        new ChatResponse(
                                "id",
                                "gpt-4o-mini",
                                "ok",
                                "stop",
                                null,
                                new ChatResponse.Usage(1, 1, 2)));

        ChatRequest req = new ChatRequest(null, List.of(ChatMessage.user("hi")));
        ChatResponse resp = gateway.chat(req);
        assertThat(resp.content()).isEqualTo("ok");
    }

    @Test
    void chatThrowsWhenProviderNotFound() {
        when(providerService.getByName("unknown")).thenReturn(null);

        ChatRequest req = new ChatRequest("unknown/model", List.of(ChatMessage.user("hi")));
        assertThatThrownBy(() -> gateway.chat(req))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("not found or disabled");
    }

    @Test
    void embedResolvesAndCalls() {
        ModelProvider provider = new ModelProvider();
        provider.setBaseUrl("https://api.openai.com/v1");
        provider.setApiKeyEncrypted("enc");
        provider.setEnabled(true);
        when(providerService.getByName("openai")).thenReturn(provider);
        when(providerService.decryptApiKey("enc")).thenReturn("sk-test");
        when(client.embed(any(), any(), any()))
                .thenReturn(
                        new EmbeddingResponse(
                                "text-embedding-3-small", List.of(new float[] {0.1f, 0.2f}), 10));

        EmbeddingRequest req = new EmbeddingRequest(null, List.of("hello"));
        EmbeddingResponse resp = gateway.embed(req);
        assertThat(resp.embeddings()).hasSize(1);
    }
}
