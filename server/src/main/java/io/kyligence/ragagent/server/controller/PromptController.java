package io.kyligence.ragagent.server.controller;

import io.kyligence.ragagent.core.prompt.Prompt;
import io.kyligence.ragagent.core.prompt.PromptService;
import io.kyligence.ragagent.shared.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/prompts")
public class PromptController {

    public record SavePromptRequest(
            @NotBlank @Size(max = 128) String name,
            @NotBlank @Size(max = 32) String version,
            @NotBlank String template,
            String variablesSchema,
            @Size(max = 512) String description) {}

    public record RenderRequest(Map<String, Object> variables) {}

    private final PromptService promptService;

    public PromptController(PromptService promptService) {
        this.promptService = promptService;
    }

    @GetMapping
    public ApiResponse<List<Prompt>> list(@PathVariable Long workspaceId) {
        return ApiResponse.ok(promptService.list(workspaceId));
    }

    @PutMapping
    public ApiResponse<Prompt> save(@PathVariable Long workspaceId,
                                    @Valid @RequestBody SavePromptRequest req) {
        return ApiResponse.ok(promptService.save(workspaceId,
                new PromptService.SavePromptCommand(
                        req.name(), req.version(), req.template(),
                        req.variablesSchema(), req.description())));
    }

    @GetMapping("/{name}/{version}")
    public ApiResponse<Prompt> get(@PathVariable Long workspaceId,
                                   @PathVariable String name,
                                   @PathVariable String version) {
        Prompt p = promptService.get(workspaceId, name, version);
        if (p == null) return ApiResponse.error("7005", "Prompt not found");
        return ApiResponse.ok(p);
    }

    @PostMapping("/{name}/{version}/render")
    public ApiResponse<String> render(@PathVariable Long workspaceId,
                                      @PathVariable String name,
                                      @PathVariable String version,
                                      @RequestBody(required = false) RenderRequest req) {
        Map<String, Object> vars = req != null ? req.variables() : Map.of();
        return ApiResponse.ok(promptService.render(workspaceId, name, version, vars));
    }

    @DeleteMapping("/{name}/{version}")
    public ApiResponse<Void> delete(@PathVariable Long workspaceId,
                                    @PathVariable String name,
                                    @PathVariable String version) {
        promptService.delete(workspaceId, name, version);
        return ApiResponse.ok(null);
    }
}
