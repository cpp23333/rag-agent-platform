package io.kyligence.ragagent.core.model;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "ragagent.model-gateway")
public class ModelGatewayProperties {
    private String defaultChat = "openai/gpt-4o-mini";
    private String fallbackChat;
    private String defaultEmbedding = "openai/text-embedding-3-small";
    private String defaultRerank;
    private int retryMaxAttempts = 3;
    private long timeoutSeconds = 120;
}
