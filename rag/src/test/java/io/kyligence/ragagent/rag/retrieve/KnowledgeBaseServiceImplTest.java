package io.kyligence.ragagent.rag.retrieve;

import io.kyligence.ragagent.rag.domain.KnowledgeBase;
import io.kyligence.ragagent.rag.domain.KnowledgeBaseMapper;
import io.kyligence.ragagent.rag.ingest.IngestService;
import io.kyligence.ragagent.shared.exception.PlatformException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class KnowledgeBaseServiceImplTest {

    @Mock KnowledgeBaseMapper kbMapper;
    @Mock IngestService ingestService;
    @Mock RerankingRetriever rerankingRetriever;

    KnowledgeBaseServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new KnowledgeBaseServiceImpl(kbMapper, ingestService, rerankingRetriever);
    }

    @Test
    void createInsertsKb() {
        when(kbMapper.insert(any())).thenReturn(1);
        KnowledgeBase kb = service.create(1L,
                new KnowledgeBaseService.CreateKbCommand(
                        "my-kb", "desc", "openai/text-embedding-3-small", 1536));
        assertThat(kb.getName()).isEqualTo("my-kb");
        assertThat(kb.getVectorDim()).isEqualTo(1536);
        verify(kbMapper).insert(any());
    }

    @Test
    void getThrowsWhenNotFound() {
        when(kbMapper.selectById("x")).thenReturn(null);
        assertThatThrownBy(() -> service.get("x"))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("not found");
    }
}
