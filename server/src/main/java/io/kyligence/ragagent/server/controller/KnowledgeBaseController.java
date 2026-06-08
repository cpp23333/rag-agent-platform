package io.kyligence.ragagent.server.controller;

import io.kyligence.ragagent.rag.domain.KnowledgeBase;
import io.kyligence.ragagent.rag.retrieve.KnowledgeBaseService;
import io.kyligence.ragagent.rag.retrieve.RetrievalResult;
import io.kyligence.ragagent.rag.retrieve.RetrieveOptions;
import io.kyligence.ragagent.rag.retrieve.RetrieveQuery;
import io.kyligence.ragagent.shared.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/knowledge-bases")
@RequiredArgsConstructor
public class KnowledgeBaseController {

    public record CreateKbRequest(
            @NotBlank String name,
            String description,
            @NotBlank String embedModel,
            @Min(256) @Max(4096) int vectorDim) {}

    public record RetrieveRequest(
            @NotBlank String text,
            @Min(1) @Max(20) int topK,
            Boolean rerank,
            String rerankModel) {}

    private final KnowledgeBaseService kbService;

    @GetMapping
    public ApiResponse<List<KnowledgeBase>> list(@PathVariable Long workspaceId) {
        return ApiResponse.ok(kbService.list(workspaceId));
    }

    @PostMapping
    public ApiResponse<KnowledgeBase> create(@PathVariable Long workspaceId,
                                              @Valid @RequestBody CreateKbRequest req) {
        return ApiResponse.ok(kbService.create(workspaceId,
                new KnowledgeBaseService.CreateKbCommand(
                        req.name(), req.description(), req.embedModel(), req.vectorDim())));
    }

    @GetMapping("/{kbId}")
    public ApiResponse<KnowledgeBase> get(@PathVariable String kbId) {
        return ApiResponse.ok(kbService.get(kbId));
    }

    @DeleteMapping("/{kbId}")
    public ApiResponse<Void> delete(@PathVariable String kbId) {
        kbService.delete(kbId);
        return ApiResponse.ok(null);
    }

    @PostMapping("/{kbId}/retrieve")
    public ApiResponse<List<RetrievalResult>> retrieve(@PathVariable String kbId,
                                                        @Valid @RequestBody RetrieveRequest req) {
        boolean useRerank = req.rerank() == null || req.rerank();
        RetrieveOptions opts = new RetrieveOptions(useRerank, req.rerankModel(), 20);
        RetrieveQuery query = new RetrieveQuery(kbId, req.text(), req.topK(), Map.of(), opts);
        return ApiResponse.ok(kbService.retrieve(query));
    }
}
