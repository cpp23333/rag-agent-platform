package io.kyligence.ragagent.rag.ingest;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.kyligence.ragagent.core.event.PlatformEventBus;
import io.kyligence.ragagent.rag.domain.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class IngestServiceImpl implements IngestService {

    private final KnowledgeBaseMapper kbMapper;
    private final RagDocMapper docMapper;
    private final RagChunkMapper chunkMapper;
    private final IngestJobMapper jobMapper;
    private final ChunkVectorRepository vectorRepo;
    private final IngestPipeline pipeline;
    private final PlatformEventBus eventBus;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public IngestResult ingest(Long workspaceId, IngestRequest request) {
        KnowledgeBase kb = kbMapper.selectById(request.kbId());
        if (kb == null) throw new IllegalArgumentException("KB not found: " + request.kbId());

        // Idempotency: check existing doc by source + will be checked via content_hash after load
        IngestJob job = new IngestJob();
        job.setKbId(request.kbId());
        job.setWorkspaceId(workspaceId);
        job.setStatus("PENDING");
        job.setProgress(0);
        job.setTotalDocs(1);
        job.setCreatedAt(LocalDateTime.now());
        jobMapper.insert(job);

        // Async execution
        runAsync(workspaceId, request, kb, job.getId());
        return new IngestResult(job.getId(), null, 0);
    }

    @Async
    public void runAsync(Long workspaceId, IngestRequest request,
                         KnowledgeBase kb, String jobId) {
        IngestJob job = jobMapper.selectById(jobId);
        job.setStatus("RUNNING");
        jobMapper.updateById(job);

        try {
            boolean isPdf = "PDF".equalsIgnoreCase(request.sourceType());
            IngestPipeline.PipelineResult result =
                pipeline.process(request, kb.getEmbedModel(), isPdf);

            // Check idempotency by content hash
            RagDoc existing = docMapper.selectOne(
                Wrappers.<RagDoc>lambdaQuery()
                    .eq(RagDoc::getKbId, request.kbId())
                    .eq(RagDoc::getContentHash, result.doc().contentHash()));
            if (existing != null) {
                log.info("Document already ingested, skipping: {}", request.source());
                job.setStatus("DONE");
                job.setFinishedAt(LocalDateTime.now());
                jobMapper.updateById(job);
                return;
            }

            // Persist doc
            RagDoc doc = new RagDoc();
            doc.setKbId(request.kbId());
            doc.setWorkspaceId(workspaceId);
            doc.setSource(request.source());
            doc.setContentHash(result.doc().contentHash());
            doc.setStatus("INDEXING");
            doc.setCreatedAt(LocalDateTime.now());
            docMapper.insert(doc);

            // Persist chunks + vectors
            List<RawChunk> chunks = result.chunks();
            List<float[]> embeddings = result.embeddings();
            for (int i = 0; i < chunks.size(); i++) {
                RawChunk rc = chunks.get(i);
                RagChunk chunk = new RagChunk();
                chunk.setKbId(request.kbId());
                chunk.setDocId(doc.getId());
                chunk.setWorkspaceId(workspaceId);
                chunk.setText(rc.text());
                chunk.setMetadataJson(objectMapper.writeValueAsString(rc.metadata()));
                chunk.setPosition(rc.position());
                chunk.setModality(rc.modality());
                chunk.setCreatedAt(LocalDateTime.now());
                chunkMapper.insert(chunk);
                vectorRepo.upsert(chunk.getId(), request.kbId(), workspaceId,
                    embeddings.get(i), kb.getEmbedModel());
            }

            doc.setStatus("DONE");
            doc.setChunkCount(chunks.size());
            doc.setIngestedAt(LocalDateTime.now());
            docMapper.updateById(doc);

            job.setStatus("DONE");
            job.setProgress(1);
            job.setFinishedAt(LocalDateTime.now());
            jobMapper.updateById(job);

            eventBus.publishIngestJobCompleted(jobId, request.kbId(),
                String.valueOf(workspaceId), true, null);

        } catch (Exception e) {
            log.error("Ingest failed for {}: {}", request.source(), e.getMessage(), e);
            job.setStatus("FAILED");
            job.setErrorMessage(e.getMessage());
            job.setFinishedAt(LocalDateTime.now());
            jobMapper.updateById(job);
            eventBus.publishIngestJobCompleted(jobId, request.kbId(),
                String.valueOf(workspaceId), false, e.getMessage());
        }
    }

    @Override
    public IngestService.IngestJobStatus getJobStatus(String jobId) {
        IngestJob job = jobMapper.selectById(jobId);
        if (job == null) throw new IllegalArgumentException("Job not found: " + jobId);
        return new IngestJobStatus(job.getId(), job.getStatus(),
            job.getProgress(), job.getTotalDocs(), job.getErrorMessage());
    }

    @Override
    @Transactional
    public void deleteDoc(String docId, Long workspaceId) {
        RagDoc doc = docMapper.selectById(docId);
        if (doc == null) return;
        List<RagChunk> chunks = chunkMapper.selectList(
            Wrappers.<RagChunk>lambdaQuery().eq(RagChunk::getDocId, docId));
        List<String> chunkIds = chunks.stream().map(RagChunk::getId).toList();
        vectorRepo.deleteByDocId(chunkIds);
        chunkMapper.delete(Wrappers.<RagChunk>lambdaQuery().eq(RagChunk::getDocId, docId));
        docMapper.deleteById(docId);
    }
}
