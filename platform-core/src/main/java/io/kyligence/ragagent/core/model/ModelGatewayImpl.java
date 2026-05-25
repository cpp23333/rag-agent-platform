package io.kyligence.ragagent.core.model;

import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.kyligence.ragagent.core.tracing.StepType;
import io.kyligence.ragagent.core.tracing.TracingService;
import io.kyligence.ragagent.shared.exception.ErrorCode;
import io.kyligence.ragagent.shared.exception.PlatformException;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

@Slf4j
@Service
public class ModelGatewayImpl implements ModelGateway {

    private final ModelProviderService providerService;
    private final ProviderClient client;
    private final ModelGatewayProperties props;
    private final TracingService tracingService;
    private final Retry retry;

    public ModelGatewayImpl(
            ModelProviderService providerService,
            ProviderClient client,
            ModelGatewayProperties props,
            TracingService tracingService) {
        this.providerService = providerService;
        this.client = client;
        this.props = props;
        this.tracingService = tracingService;
        this.retry =
                Retry.of(
                        "model-gateway",
                        RetryConfig.custom()
                                .maxAttempts(props.getRetryMaxAttempts())
                                .waitDuration(Duration.ofMillis(500))
                                .retryExceptions(PlatformException.class)
                                .build());
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        String modelRef = request.model() != null ? request.model() : props.getDefaultChat();
        ResolvedProvider resolved = resolve(modelRef);
        long start = System.currentTimeMillis();

        ChatResponse resp =
                withRetry(() -> client.chat(resolved.baseUrl(), resolved.apiKey(), request));

        long latency = System.currentTimeMillis() - start;
        recordStep(
                modelRef,
                request.messages().toString(),
                resp.content(),
                resp.usage() != null ? resp.usage().totalTokens() : 0,
                latency);
        return resp;
    }

    @Override
    public Flux<String> chatStream(ChatRequest request) {
        String modelRef = request.model() != null ? request.model() : props.getDefaultChat();
        ResolvedProvider resolved = resolve(modelRef);
        return client.chatStream(resolved.baseUrl(), resolved.apiKey(), request);
    }

    @Override
    public EmbeddingResponse embed(EmbeddingRequest request) {
        String modelRef =
                request.model() != null ? request.model() : props.getDefaultEmbedding();
        ResolvedProvider resolved = resolve(modelRef);
        long start = System.currentTimeMillis();

        EmbeddingResponse resp =
                withRetry(() -> client.embed(resolved.baseUrl(), resolved.apiKey(), request));

        long latency = System.currentTimeMillis() - start;
        recordStep(
                modelRef,
                request.inputs().size() + " inputs",
                "embeddings",
                resp.totalTokens(),
                latency);
        return resp;
    }

    @Override
    public RerankResponse rerank(RerankRequest request) {
        String modelRef = request.model() != null ? request.model() : props.getDefaultRerank();
        if (modelRef == null) {
            throw new PlatformException(
                    ErrorCode.MODEL_PROVIDER_NOT_FOUND, "No default rerank model configured");
        }
        ResolvedProvider resolved = resolve(modelRef);
        long start = System.currentTimeMillis();

        RerankResponse resp =
                withRetry(() -> client.rerank(resolved.baseUrl(), resolved.apiKey(), request));

        long latency = System.currentTimeMillis() - start;
        recordStep(
                modelRef, request.query(), resp.results().size() + " results", 0, latency);
        return resp;
    }

    private record ResolvedProvider(String baseUrl, String apiKey) {}

    private ResolvedProvider resolve(String modelRef) {
        int slash = modelRef.indexOf('/');
        if (slash < 0) {
            throw new PlatformException(
                    ErrorCode.MODEL_PROVIDER_NOT_FOUND,
                    "Invalid model reference (expected provider/model): " + modelRef);
        }
        String providerName = modelRef.substring(0, slash);
        ModelProvider provider = providerService.getByName(providerName);
        if (provider == null || !provider.getEnabled()) {
            throw new PlatformException(
                    ErrorCode.MODEL_PROVIDER_NOT_FOUND,
                    "Provider not found or disabled: " + providerName);
        }
        String apiKey = providerService.decryptApiKey(provider.getApiKeyEncrypted());
        return new ResolvedProvider(provider.getBaseUrl(), apiKey);
    }

    private <T> T withRetry(Supplier<T> supplier) {
        return Retry.decorateSupplier(retry, supplier).get();
    }

    private void recordStep(
            String model, String input, String output, int tokens, long latencyMs) {
        try {
            tracingService.addStep(
                    null,
                    "model:" + model,
                    StepType.LLM,
                    truncate(input, 2000),
                    truncate(output, 2000),
                    tokens,
                    BigDecimal.ZERO,
                    latencyMs);
        } catch (Exception e) {
            log.warn("Failed to record tracing step: {}", e.getMessage());
        }
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
