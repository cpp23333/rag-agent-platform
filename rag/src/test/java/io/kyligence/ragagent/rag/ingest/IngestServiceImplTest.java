package io.kyligence.ragagent.rag.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.kyligence.ragagent.core.event.PlatformEventBus;
import io.kyligence.ragagent.rag.domain.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IngestServiceImplTest {

    @Mock KnowledgeBaseMapper kbMapper;
    @Mock RagDocMapper docMapper;
    @Mock RagChunkMapper chunkMapper;
    @Mock IngestJobMapper jobMapper;
    @Mock ChunkVectorRepository vectorRepo;
    @Mock IngestPipeline pipeline;
    @Mock PlatformEventBus eventBus;

    IngestServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new IngestServiceImpl(kbMapper, docMapper, chunkMapper, jobMapper,
            vectorRepo, pipeline, eventBus, new ObjectMapper());
    }

    @Test
    void ingestThrowsWhenKbNotFound() {
        when(kbMapper.selectById(any())).thenReturn(null);
        assertThatThrownBy(() ->
            service.ingest(1L, new IngestRequest("kb-1", "test.txt", "TXT",
                "hello".getBytes())))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("KB not found");
    }

    @Test
    void ingestCreatesJobAndReturnsJobId() {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId("kb-1"); kb.setEmbedModel("openai/text-embedding-3-small");
        when(kbMapper.selectById("kb-1")).thenReturn(kb);
        when(jobMapper.insert(any())).thenAnswer(inv -> {
            ((IngestJob) inv.getArgument(0)).setId("job-1"); return 1; });
        IngestJob trackedJob = new IngestJob(); trackedJob.setId("job-1");
        when(jobMapper.selectById("job-1")).thenReturn(trackedJob);
        when(pipeline.process(any(), any(), anyBoolean())).thenThrow(
            new RuntimeException("pipeline not configured in this test"));

        IngestResult result = service.ingest(1L,
            new IngestRequest("kb-1", "test.txt", "TXT", "hello".getBytes()));

        assertThat(result.jobId()).isEqualTo("job-1");
        verify(jobMapper).insert(any(IngestJob.class));
    }

    @Test
    void runAsyncSkipsAlreadyIngestedDoc() {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId("kb-1"); kb.setEmbedModel("openai/text-embedding-3-small");

        RawDocument rawDoc = new RawDocument("src", "hash123",
            List.of(new RawDocument.RawSegment("text", "text", java.util.Map.of())));
        when(pipeline.process(any(), any(), anyBoolean())).thenReturn(
            new IngestPipeline.PipelineResult(rawDoc, List.of(), List.of()));

        RagDoc existing = new RagDoc(); existing.setId("doc-old");
        when(docMapper.selectOne(any())).thenReturn(existing);

        IngestJob job = new IngestJob(); job.setId("job-1");
        when(jobMapper.selectById("job-1")).thenReturn(job);

        service.runAsync(1L, new IngestRequest("kb-1", "src", "TXT", "x".getBytes()),
            kb, "job-1");

        verify(docMapper, never()).insert(any());
        assertThat(job.getStatus()).isEqualTo("DONE");
    }
}
