package io.kyligence.ragagent.server.controller;

import io.kyligence.ragagent.rag.ingest.IngestRequest;
import io.kyligence.ragagent.rag.ingest.IngestResult;
import io.kyligence.ragagent.rag.ingest.IngestService;
import io.kyligence.ragagent.shared.api.ApiResponse;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/knowledge-bases/{kbId}/ingest")
@RequiredArgsConstructor
public class IngestController {

    private final IngestService ingestService;

    @PostMapping(value = "/file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<IngestResult> ingestFile(
            @PathVariable Long workspaceId,
            @PathVariable String kbId,
            @RequestParam("file") MultipartFile file,
            @RequestParam("sourceType") String sourceType) throws IOException {
        IngestRequest req = new IngestRequest(kbId, file.getOriginalFilename(),
                sourceType.toUpperCase(), file.getBytes());
        return ApiResponse.ok(ingestService.ingest(workspaceId, req));
    }

    @PostMapping("/url")
    public ApiResponse<IngestResult> ingestUrl(
            @PathVariable Long workspaceId,
            @PathVariable String kbId,
            @RequestBody UrlRequest req) {
        return ApiResponse.ok(ingestService.ingest(workspaceId,
                new IngestRequest(kbId, req.url(), "URL", null)));
    }

    @GetMapping("/jobs/{jobId}")
    public ApiResponse<IngestService.IngestJobStatus> jobStatus(
            @PathVariable String jobId) {
        return ApiResponse.ok(ingestService.getJobStatus(jobId));
    }

    @DeleteMapping("/docs/{docId}")
    public ApiResponse<Void> deleteDoc(
            @PathVariable Long workspaceId,
            @PathVariable String docId) {
        ingestService.deleteDoc(docId, workspaceId);
        return ApiResponse.ok(null);
    }

    public record UrlRequest(@NotBlank String url) {}
}
