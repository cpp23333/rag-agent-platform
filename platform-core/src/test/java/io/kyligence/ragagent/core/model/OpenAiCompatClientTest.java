package io.kyligence.ragagent.core.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@ExtendWith(MockitoExtension.class)
class OpenAiCompatClientTest {

    @Mock private WebClient.Builder webClientBuilder;
    @Mock private WebClient webClient;
    @Mock private WebClient.RequestBodyUriSpec requestBodyUriSpec;
    @Mock private WebClient.RequestBodySpec requestBodySpec;
    @Mock private WebClient.RequestHeadersSpec requestHeadersSpec;
    @Mock private WebClient.ResponseSpec responseSpec;
    @Mock private ModelGatewayProperties properties;

    private OpenAiCompatClient client;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        when(properties.getTimeoutSeconds()).thenReturn(120L);
        client = new OpenAiCompatClient(webClientBuilder, objectMapper, properties);
    }

    @Test
    void chatBuildsCorrectRequest() {
        String mockResponse =
                """
                {
                  "id": "chatcmpl-123",
                  "model": "gpt-4o-mini",
                  "choices": [{
                    "message": {"role": "assistant", "content": "Hello!"},
                    "finish_reason": "stop"
                  }],
                  "usage": {"prompt_tokens": 10, "completion_tokens": 5, "total_tokens": 15}
                }
                """;

        when(webClientBuilder.build()).thenReturn(webClient);
        when(webClient.post()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(any(String.class))).thenReturn(requestBodySpec);
        when(requestBodySpec.header(any(), any())).thenReturn(requestBodySpec);
        when(requestBodySpec.contentType(any())).thenReturn(requestBodySpec);
        when(requestBodySpec.bodyValue(any())).thenReturn(requestHeadersSpec);
        when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.bodyToMono(String.class)).thenReturn(Mono.just(mockResponse));

        ChatRequest req =
                new ChatRequest("openai/gpt-4o-mini", List.of(ChatMessage.user("Hi")));
        ChatResponse resp = client.chat("https://api.openai.com/v1", "sk-test", req);

        assertThat(resp.content()).isEqualTo("Hello!");
        assertThat(resp.usage().totalTokens()).isEqualTo(15);
    }

    @Test
    void chatResponseReturnsImmutableToolCalls() {
        String mockResponse =
                """
                {
                  "id": "chatcmpl-123",
                  "model": "gpt-4o-mini",
                  "choices": [{
                    "message": {
                      "role": "assistant",
                      "content": null,
                      "tool_calls": [{
                        "id": "call_1",
                        "function": {"name": "get_weather", "arguments": "{}"}
                      }]
                    },
                    "finish_reason": "tool_calls"
                  }],
                  "usage": {"prompt_tokens": 10, "completion_tokens": 5, "total_tokens": 15}
                }
                """;

        when(webClientBuilder.build()).thenReturn(webClient);
        when(webClient.post()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(any(String.class))).thenReturn(requestBodySpec);
        when(requestBodySpec.header(any(), any())).thenReturn(requestBodySpec);
        when(requestBodySpec.contentType(any())).thenReturn(requestBodySpec);
        when(requestBodySpec.bodyValue(any())).thenReturn(requestHeadersSpec);
        when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.bodyToMono(String.class)).thenReturn(Mono.just(mockResponse));

        ChatRequest req =
                new ChatRequest("openai/gpt-4o-mini", List.of(ChatMessage.user("Hi")));
        ChatResponse resp = client.chat("https://api.openai.com/v1", "sk-test", req);

        assertThat(resp.toolCalls()).isNotNull();
        assertThatThrownBy(() -> resp.toolCalls().add(null))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
