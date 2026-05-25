package io.kyligence.ragagent.core.model;

import java.util.List;

public interface ModelProviderService {

    ModelProvider create(Long workspaceId, CreateProviderCommand cmd);

    ModelProvider update(String id, UpdateProviderCommand cmd);

    ModelProvider getByName(String name);

    ModelProvider getById(String id);

    List<ModelProvider> list(Long workspaceId);

    void delete(String id);

    String decryptApiKey(String encrypted);

    record CreateProviderCommand(
            String name, String type, String baseUrl, String apiKey, String modelsJson) {}

    record UpdateProviderCommand(
            String baseUrl, String apiKey, String modelsJson, Boolean enabled) {}
}
