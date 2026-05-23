package io.kyligence.ragagent.core.prompt;

import java.util.List;
import java.util.Map;

public interface PromptService {

    Prompt save(Long workspaceId, SavePromptCommand cmd);

    String render(Long workspaceId, String name, String version, Map<String, Object> variables);

    default String renderUri(Long workspaceId, String uri, Map<String, Object> variables) {
        PromptUri p = PromptUri.parse(uri);
        return render(workspaceId, p.name(), p.version(), variables);
    }

    Prompt get(Long workspaceId, String name, String version);

    List<Prompt> list(Long workspaceId);

    void delete(Long workspaceId, String name, String version);

    record SavePromptCommand(
            String name, String version, String template,
            String variablesSchema, String description) {}
}
