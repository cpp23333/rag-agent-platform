package io.kyligence.ragagent.server.controller;

import io.kyligence.ragagent.core.model.ModelProvider;
import io.kyligence.ragagent.core.model.ModelProviderService;
import io.kyligence.ragagent.shared.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/model-providers")
public class ModelProviderController {

    public record CreateRequest(
            @NotBlank @Size(max = 64) String name,
            @NotBlank @Size(max = 32) String type,
            @NotBlank @Size(max = 512) String baseUrl,
            @NotBlank @Size(max = 256) String apiKey,
            @Size(max = 10000) String modelsJson) {}

    public record UpdateRequest(
            @Size(max = 64) String name,
            @Size(max = 512) String baseUrl,
            @Size(max = 256) String apiKey,
            @Size(max = 10000) String modelsJson,
            Boolean enabled) {}

    public record ProviderResponse(
            String id,
            String name,
            String type,
            String baseUrl,
            String modelsJson,
            Boolean enabled,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {
        static ProviderResponse from(ModelProvider p) {
            return new ProviderResponse(
                    p.getId(),
                    p.getName(),
                    p.getType(),
                    p.getBaseUrl(),
                    p.getModelsJson(),
                    p.getEnabled(),
                    p.getCreatedAt(),
                    p.getUpdatedAt());
        }
    }

    private final ModelProviderService service;

    public ModelProviderController(ModelProviderService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<ProviderResponse>> list(@PathVariable Long workspaceId) {
        return ApiResponse.ok(
                service.list(workspaceId).stream().map(ProviderResponse::from).toList());
    }

    @PostMapping
    public ApiResponse<ProviderResponse> create(
            @PathVariable Long workspaceId, @Valid @RequestBody CreateRequest req) {
        ModelProvider provider =
                service.create(
                        workspaceId,
                        new ModelProviderService.CreateProviderCommand(
                                req.name(), req.type(), req.baseUrl(), req.apiKey(),
                                req.modelsJson()));
        return ApiResponse.ok(ProviderResponse.from(provider));
    }

    @PutMapping("/{id}")
    public ApiResponse<ProviderResponse> update(
            @PathVariable String id, @Valid @RequestBody UpdateRequest req) {
        ModelProvider updated =
                service.update(
                        id,
                        new ModelProviderService.UpdateProviderCommand(
                                req.baseUrl(), req.apiKey(), req.modelsJson(), req.enabled()));
        return ApiResponse.ok(ProviderResponse.from(updated));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable String id) {
        service.delete(id);
        return ApiResponse.ok(null);
    }
}
