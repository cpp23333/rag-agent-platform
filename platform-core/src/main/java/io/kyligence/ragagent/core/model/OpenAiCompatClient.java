package io.kyligence.ragagent.core.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.kyligence.ragagent.shared.exception.ErrorCode;
import io.kyligence.ragagent.shared.exception.PlatformException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

@Slf4j
@Component
@RequiredArgsConstructor
public class OpenAiCompatClient implements ProviderClient {

    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper;
    private final ModelGatewayProperties properties;

    private Duration getTimeout() {
        return Duration.ofSeconds(properties.getTimeoutSeconds());
    }

    @Override
    public ChatResponse chat(String baseUrl, String apiKey, ChatRequest request) {
        ObjectNode body = buildChatBody(request, false);
        JsonNode resp = post(baseUrl, "/chat/completions", apiKey, body);
        return parseChatResponse(resp);
    }

    @Override
    public Flux<String> chatStream(String baseUrl, String apiKey, ChatRequest request) {
        ObjectNode body = buildChatBody(request, true);
        return webClientBuilder
                .build()
                .post()
                .uri(baseUrl + "/chat/completions")
                .header("Authorization", "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body.toString())
                .retrieve()
                .bodyToFlux(String.class)
                .timeout(getTimeout());
    }

    @Override
    public EmbeddingResponse embed(String baseUrl, String apiKey, EmbeddingRequest request) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", request.model());
        ArrayNode inputs = body.putArray("input");
        request.inputs().forEach(inputs::add);

        JsonNode resp = post(baseUrl, "/embeddings", apiKey, body);
        return parseEmbeddingResponse(resp, request.model());
    }

    @Override
    public RerankResponse rerank(String baseUrl, String apiKey, RerankRequest request) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", request.model());
        body.put("query", request.query());
        ArrayNode docs = body.putArray("documents");
        request.documents().forEach(docs::add);
        body.put("top_n", request.topN());

        JsonNode resp = post(baseUrl, "/rerank", apiKey, body);
        return parseRerankResponse(resp, request);
    }

    private ObjectNode buildChatBody(ChatRequest req, boolean stream) {
        ObjectNode body = objectMapper.createObjectNode();
        String model = req.model() != null ? resolveModelName(req.model()) : "gpt-4o-mini";
        body.put("model", model);
        body.put("stream", stream);
        if (req.temperature() != null) body.put("temperature", req.temperature());
        if (req.maxTokens() != null) body.put("max_tokens", req.maxTokens());

        ArrayNode msgs = body.putArray("messages");
        for (ChatMessage m : req.messages()) {
            ObjectNode msg = msgs.addObject();
            msg.put("role", m.role());
            msg.put("content", m.content());
            if (m.name() != null) msg.put("name", m.name());
        }
        if (req.tools() != null && !req.tools().isEmpty()) {
            body.set("tools", objectMapper.valueToTree(req.tools()));
        }
        return body;
    }

    private JsonNode post(String baseUrl, String path, String apiKey, ObjectNode body) {
        try {
            String response =
                    webClientBuilder
                            .build()
                            .post()
                            .uri(baseUrl + path)
                            .header("Authorization", "Bearer " + apiKey)
                            .contentType(MediaType.APPLICATION_JSON)
                            .bodyValue(body.toString())
                            .retrieve()
                            .bodyToMono(String.class)
                            .timeout(getTimeout())
                            .block();
            return objectMapper.readTree(response);
        } catch (Exception e) {
            throw new PlatformException(
                    ErrorCode.MODEL_CALL_FAILED, "Model API call failed: " + e.getMessage(), e);
        }
    }

    private String resolveModelName(String qualifiedModel) {
        int slash = qualifiedModel.indexOf('/');
        return slash >= 0 ? qualifiedModel.substring(slash + 1) : qualifiedModel;
    }

    private ChatResponse parseChatResponse(JsonNode resp) {
        JsonNode choice = resp.path("choices").path(0);
        JsonNode message = choice.path("message");
        String content = message.path("content").asText(null);
        String finishReason = choice.path("finish_reason").asText(null);

        List<ChatResponse.ToolCall> toolCalls = new ArrayList<>();
        if (message.has("tool_calls")) {
            for (JsonNode tc : message.path("tool_calls")) {
                toolCalls.add(
                        new ChatResponse.ToolCall(
                                tc.path("id").asText(),
                                tc.path("function").path("name").asText(),
                                tc.path("function").path("arguments").asText()));
            }
        }

        JsonNode usage = resp.path("usage");
        ChatResponse.Usage u =
                new ChatResponse.Usage(
                        usage.path("prompt_tokens").asInt(0),
                        usage.path("completion_tokens").asInt(0),
                        usage.path("total_tokens").asInt(0));

        return new ChatResponse(
                resp.path("id").asText(null),
                resp.path("model").asText(null),
                content,
                finishReason,
                toolCalls.isEmpty() ? null : List.copyOf(toolCalls),
                u);
    }

    private EmbeddingResponse parseEmbeddingResponse(JsonNode resp, String model) {
        List<float[]> embeddings = new ArrayList<>();
        for (JsonNode item : resp.path("data")) {
            JsonNode emb = item.path("embedding");
            float[] vec = new float[emb.size()];
            for (int i = 0; i < emb.size(); i++) vec[i] = (float) emb.get(i).asDouble();
            embeddings.add(vec);
        }
        int tokens = resp.path("usage").path("total_tokens").asInt(0);
        return new EmbeddingResponse(model, List.copyOf(embeddings), tokens);
    }

    private RerankResponse parseRerankResponse(JsonNode resp, RerankRequest req) {
        List<RerankResponse.ScoredDocument> results = new ArrayList<>();
        for (JsonNode item : resp.path("results")) {
            int idx = item.path("index").asInt();
            double score = item.path("relevance_score").asDouble();
            String text = idx < req.documents().size() ? req.documents().get(idx) : "";
            results.add(new RerankResponse.ScoredDocument(idx, score, text));
        }
        return new RerankResponse(req.model(), List.copyOf(results));
    }
}
