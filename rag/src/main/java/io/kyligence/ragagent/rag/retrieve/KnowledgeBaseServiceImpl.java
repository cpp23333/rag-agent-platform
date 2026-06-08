package io.kyligence.ragagent.rag.retrieve;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.kyligence.ragagent.rag.domain.KnowledgeBase;
import io.kyligence.ragagent.rag.domain.KnowledgeBaseMapper;
import io.kyligence.ragagent.rag.ingest.IngestRequest;
import io.kyligence.ragagent.rag.ingest.IngestResult;
import io.kyligence.ragagent.rag.ingest.IngestService;
import io.kyligence.ragagent.shared.exception.ErrorCode;
import io.kyligence.ragagent.shared.exception.PlatformException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class KnowledgeBaseServiceImpl implements KnowledgeBaseService {

    private final KnowledgeBaseMapper kbMapper;
    private final IngestService ingestService;
    private final RerankingRetriever rerankingRetriever;

    @Override
    @Transactional
    public KnowledgeBase create(Long workspaceId, CreateKbCommand cmd) {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setWorkspaceId(workspaceId);
        kb.setName(cmd.name());
        kb.setDescription(cmd.description());
        kb.setEmbedModel(cmd.embedModel());
        kb.setVectorDim(cmd.vectorDim());
        kb.setCreatedAt(LocalDateTime.now());
        kb.setUpdatedAt(LocalDateTime.now());
        kbMapper.insert(kb);
        return kb;
    }

    @Override
    public KnowledgeBase get(String kbId) {
        KnowledgeBase kb = kbMapper.selectById(kbId);
        if (kb == null) throw new PlatformException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND, "KB not found: " + kbId);
        return kb;
    }

    @Override
    public List<KnowledgeBase> list(Long workspaceId) {
        return kbMapper.selectList(
                Wrappers.<KnowledgeBase>lambdaQuery()
                        .eq(KnowledgeBase::getWorkspaceId, workspaceId)
                        .orderByAsc(KnowledgeBase::getName));
    }

    @Override
    @Transactional
    public void delete(String kbId) {
        kbMapper.deleteById(kbId);
        // chunk/vector cleanup handled by caller or scheduled job
    }

    @Override
    public IngestResult ingest(Long workspaceId, IngestRequest request) {
        return ingestService.ingest(workspaceId, request);
    }

    @Override
    public List<RetrievalResult> retrieve(RetrieveQuery query) {
        return rerankingRetriever.retrieve(query);
    }
}
