# C2 Spec: KnowledgeBase API + Retriever

## Context

C2 在 C1 的 Ingest 基础上，实现知识库管理 CRUD 和检索链路（架构设计 §4.4）：
```
Query → VectorRetriever(pgvector HNSW) → Rerank(bge-reranker) → top_k 返回
```
BM25 留接口但一期关闭。Rerank 默认开启，可通过 `options` 参数关闭。

## 范围

`rag` 模块（服务层 + 检索）+ `server` 模块（REST controller）。

---

## File Structure

```
rag/src/main/java/io/kyligence/ragagent/rag/
  retrieve/
    RetrieveQuery.java
    RetrievalResult.java
    RetrieveOptions.java
    Retriever.java                  # 接口
    VectorRetriever.java
    BM25Retriever.java              # 接口占位（no-op）
    RerankingRetriever.java         # 装饰器：包装任意 Retriever + Rerank
    KnowledgeBaseService.java       # 接口：KB CRUD + ingest + retrieve
    KnowledgeBaseServiceImpl.java

server/src/main/java/io/kyligence/ragagent/server/controller/
  KnowledgeBaseController.java
  IngestController.java
```

---

## Phase 1: Retrieve DTOs

### File: `rag/retrieve/RetrieveOptions.java`

```java
package io.kyligence.ragagent.rag.retrieve;

public record RetrieveOptions(
        boolean rerank,          // default true
        String rerankModel,      // null = use default_rerank from gateway props
        int vectorTopK           // candidates before rerank, default 20
) {
    public static RetrieveOptions defaults() {
        return new RetrieveOptions(true, null, 20);
    }
}
```

### File: `rag/retrieve/RetrieveQuery.java`

```java
package io.kyligence.ragagent.rag.retrieve;

import java.util.Map;

public record RetrieveQuery(
        String kbId,
        String text,
        int topK,
        Map<String, Object> filters,   // metadata filters (one-to-one JSON match)
        RetrieveOptions options
) {
    public RetrieveQuery(String kbId, String text, int topK) {
        this(kbId, text, topK, Map.of(), RetrieveOptions.defaults());
    }
}
```

### File: `rag/retrieve/RetrievalResult.java`

```java
package io.kyligence.ragagent.rag.retrieve;

import java.util.Map;

public record RetrievalResult(
        String chunkId,
        String docId,
        String kbId,
        String text,
        double score,
        Map<String, Object> metadata
) {}
```

---

## Phase 2: Retriever Interface & Implementations

### File: `rag/retrieve/Retriever.java`

```java
package io.kyligence.ragagent.rag.retrieve;

import java.util.List;

public interface Retriever {
    List<RetrievalResult> retrieve(RetrieveQuery query);
}
```

### File: `rag/retrieve/VectorRetriever.java`

pgvector cosine similarity 检索，返回 `vectorTopK` 个候选。

```java
package io.kyligence.ragagent.rag.retrieve;

import com.pgvector.PGvector;
import io.kyligence.ragagent.core.model.EmbeddingRequest;
import io.kyligence.ragagent.core.model.ModelGateway;
import io.kyligence.ragagent.rag.domain.RagChunk;
import io.kyligence.ragagent.rag.domain.RagChunkMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class VectorRetriever implements Retriever {

    private final ModelGateway modelGateway;
    private final JdbcTemplate pgJdbcTemplate;
    private final RagChunkMapper chunkMapper;
    private final ObjectMapper objectMapper;

    @Override
    public List<RetrievalResult> retrieve(RetrieveQuery query) {
        // 1. Embed query
        float[] queryVec = modelGateway.embed(
                new EmbeddingRequest(null, List.of(query.text())))
                .embeddings().get(0);

        // 2. pgvector ANN search
        int limit = query.options().vectorTopK();
        List<Map<String, Object>> rows = pgJdbcTemplate.queryForList(
                "SELECT chunk_id, 1 - (embedding <=> ?) AS score " +
                "FROM chunk_vector WHERE kb_id = ? " +
                "ORDER BY embedding <=> ? LIMIT ?",
                new PGvector(queryVec), query.kbId(),
                new PGvector(queryVec), limit);

        // 3. Fetch chunk text from MySQL
        List<RetrievalResult> results = new ArrayList<>();
        for (var row : rows) {
            String chunkId = (String) row.get("chunk_id");
            double score = ((Number) row.get("score")).doubleValue();
            RagChunk chunk = chunkMapper.selectById(chunkId);
            if (chunk == null) continue;
            Map<String, Object> meta = parseMetadata(chunk.getMetadataJson());
            results.add(new RetrievalResult(
                    chunkId, chunk.getDocId(), chunk.getKbId(),
                    chunk.getText(), score, meta));
        }
        return results;
    }

    private Map<String, Object> parseMetadata(String json) {
        if (json == null) return Map.of();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }
}
```

### File: `rag/retrieve/BM25Retriever.java`

一期不实现，返回空列表。

```java
package io.kyligence.ragagent.rag.retrieve;

import org.springframework.stereotype.Component;
import java.util.List;

/** BM25 hybrid retrieval — not implemented in phase 1. */
@Component
public class BM25Retriever implements Retriever {

    @Override
    public List<RetrievalResult> retrieve(RetrieveQuery query) {
        return List.of(); // no-op: BM25 disabled in phase 1
    }
}
```

### File: `rag/retrieve/RerankingRetriever.java`

装饰器：调用 `VectorRetriever` 获取候选，再调 `ModelGateway.rerank` 重排，截取 `topK`。

```java
package io.kyligence.ragagent.rag.retrieve;

import io.kyligence.ragagent.core.model.ModelGateway;
import io.kyligence.ragagent.core.model.RerankRequest;
import io.kyligence.ragagent.core.model.RerankResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class RerankingRetriever implements Retriever {

    private final VectorRetriever vectorRetriever;
    private final ModelGateway modelGateway;

    @Override
    public List<RetrievalResult> retrieve(RetrieveQuery query) {
        List<RetrievalResult> candidates = vectorRetriever.retrieve(query);
        if (candidates.isEmpty()) return candidates;

        RetrieveOptions opts = query.options();
        if (!opts.rerank()) {
            // rerank disabled: just return top_k from vector results
            return candidates.subList(0, Math.min(query.topK(), candidates.size()));
        }

        try {
            List<String> texts = candidates.stream().map(RetrievalResult::text).toList();
            RerankResponse reranked = modelGateway.rerank(new RerankRequest(
                    opts.rerankModel(), query.text(), texts, query.topK()));

            List<RetrievalResult> result = new ArrayList<>();
            for (RerankResponse.ScoredDocument sd : reranked.results()) {
                RetrievalResult original = candidates.get(sd.index());
                result.add(new RetrievalResult(
                        original.chunkId(), original.docId(), original.kbId(),
                        original.text(), sd.score(), original.metadata()));
            }
            return result;
        } catch (Exception e) {
            log.warn("Rerank failed, falling back to vector results: {}", e.getMessage());
            return candidates.subList(0, Math.min(query.topK(), candidates.size()));
        }
    }
}
```

---

## Phase 3: KnowledgeBaseService

### File: `rag/retrieve/KnowledgeBaseService.java`

```java
package io.kyligence.ragagent.rag.retrieve;

import io.kyligence.ragagent.rag.domain.IngestJob;
import io.kyligence.ragagent.rag.domain.KnowledgeBase;
import io.kyligence.ragagent.rag.ingest.IngestRequest;
import io.kyligence.ragagent.rag.ingest.IngestResult;

import java.util.List;

public interface KnowledgeBaseService {

    KnowledgeBase create(Long workspaceId, CreateKbCommand cmd);

    KnowledgeBase get(String kbId);

    List<KnowledgeBase> list(Long workspaceId);

    void delete(String kbId);

    IngestResult ingest(Long workspaceId, IngestRequest request);

    List<RetrievalResult> retrieve(RetrieveQuery query);

    record CreateKbCommand(String name, String description,
                           String embedModel, int vectorDim) {}
}
```

### File: `rag/retrieve/KnowledgeBaseServiceImpl.java`

```java
package io.kyligence.ragagent.rag.retrieve;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.kyligence.ragagent.rag.domain.*;
import io.kyligence.ragagent.rag.ingest.*;
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
        if (kb == null) throw new PlatformException(ErrorCode.NOT_FOUND, "KB not found: " + kbId);
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
```

---

## Phase 4: REST Controllers

### File: `server/.../controller/KnowledgeBaseController.java`

```java
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
```

### File: `server/.../controller/IngestController.java`

```java
package io.kyligence.ragagent.server.controller;

import io.kyligence.ragagent.rag.ingest.IngestRequest;
import io.kyligence.ragagent.rag.ingest.IngestResult;
import io.kyligence.ragagent.rag.ingest.IngestService;
import io.kyligence.ragagent.shared.api.ApiResponse;
import jakarta.validation.Valid;
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

    /** Upload a file for ingestion. */
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

    /** Ingest a URL. */
    @PostMapping("/url")
    public ApiResponse<IngestResult> ingestUrl(
            @PathVariable Long workspaceId,
            @PathVariable String kbId,
            @RequestBody UrlRequest req) {
        return ApiResponse.ok(ingestService.ingest(workspaceId,
                new IngestRequest(kbId, req.url(), "URL", null)));
    }

    /** Poll job status. */
    @GetMapping("/jobs/{jobId}")
    public ApiResponse<IngestService.IngestJobStatus> jobStatus(
            @PathVariable String jobId) {
        return ApiResponse.ok(ingestService.getJobStatus(jobId));
    }

    /** Delete an ingested document. */
    @DeleteMapping("/docs/{docId}")
    public ApiResponse<Void> deleteDoc(
            @PathVariable Long workspaceId,
            @PathVariable String docId) {
        ingestService.deleteDoc(docId, workspaceId);
        return ApiResponse.ok(null);
    }

    public record UrlRequest(@NotBlank String url) {}
}
```

---

## Phase 5: Tests

### File: `rag/retrieve/RerankingRetrieverTest.java`

```java
package io.kyligence.ragagent.rag.retrieve;

import io.kyligence.ragagent.core.model.ModelGateway;
import io.kyligence.ragagent.core.model.RerankResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RerankingRetrieverTest {

    @Mock VectorRetriever vectorRetriever;
    @Mock ModelGateway modelGateway;

    @Test
    void rerankReordersResults() {
        List<RetrievalResult> candidates = List.of(
                result("c1", "first text", 0.9),
                result("c2", "second text", 0.8),
                result("c3", "third text", 0.7));
        when(vectorRetriever.retrieve(any())).thenReturn(candidates);
        when(modelGateway.rerank(any())).thenReturn(new RerankResponse("bge",
                List.of(
                        new RerankResponse.ScoredDocument(2, 0.95, "third text"),
                        new RerankResponse.ScoredDocument(0, 0.85, "first text"))));

        RerankingRetriever retriever = new RerankingRetriever(vectorRetriever, modelGateway);
        RetrieveQuery query = new RetrieveQuery("kb1", "query", 2);
        List<RetrievalResult> results = retriever.retrieve(query);

        assertThat(results).hasSize(2);
        assertThat(results.get(0).chunkId()).isEqualTo("c3");  // reranked to top
        assertThat(results.get(0).score()).isEqualTo(0.95);
    }

    @Test
    void rerankDisabledReturnsTopKFromVector() {
        List<RetrievalResult> candidates = List.of(
                result("c1", "t1", 0.9), result("c2", "t2", 0.8), result("c3", "t3", 0.7));
        when(vectorRetriever.retrieve(any())).thenReturn(candidates);

        RerankingRetriever retriever = new RerankingRetriever(vectorRetriever, modelGateway);
        RetrieveQuery query = new RetrieveQuery("kb1", "q", 2, Map.of(),
                new RetrieveOptions(false, null, 20));
        List<RetrievalResult> results = retriever.retrieve(query);

        assertThat(results).hasSize(2);
        assertThat(results.get(0).chunkId()).isEqualTo("c1");
        verifyNoInteractions(modelGateway);
    }

    @Test
    void rerankFailureFallsBackToVectorResults() {
        List<RetrievalResult> candidates = List.of(result("c1", "t1", 0.9));
        when(vectorRetriever.retrieve(any())).thenReturn(candidates);
        when(modelGateway.rerank(any())).thenThrow(new RuntimeException("rerank unavailable"));

        RerankingRetriever retriever = new RerankingRetriever(vectorRetriever, modelGateway);
        List<RetrievalResult> results = retriever.retrieve(
                new RetrieveQuery("kb1", "q", 5));

        assertThat(results).hasSize(1);
        assertThat(results.get(0).chunkId()).isEqualTo("c1");
    }

    private RetrievalResult result(String id, String text, double score) {
        return new RetrievalResult(id, "doc1", "kb1", text, score, Map.of());
    }
}
```

### File: `rag/retrieve/KnowledgeBaseServiceImplTest.java`

```java
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
```

---

## Configuration Additions

### `server/src/main/resources/application.yml` 新增

```yaml
spring:
  datasource:
    # 已有 MySQL datasource（primary）
    postgres:
      url: jdbc:postgresql://localhost:5432/ragagent_vec
      username: ragagent
      password: ragagent
      driver-class-name: org.postgresql.Driver

ragagent:
  model-gateway:
    default-rerank: bge/bge-reranker-v2-m3   # Rerank 默认开启
```

### `server/src/main/java/.../config/PostgresDataSourceConfig.java`（新增）

```java
package io.kyligence.ragagent.server.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

@Configuration
public class PostgresDataSourceConfig {

    @Bean("pgDataSource")
    @ConfigurationProperties("spring.datasource.postgres")
    public DataSource pgDataSource() {
        return DataSourceBuilder.create().build();
    }

    @Bean("pgJdbcTemplate")
    public JdbcTemplate pgJdbcTemplate(@Qualifier("pgDataSource") DataSource ds) {
        return new JdbcTemplate(ds);
    }
}
```

---

## Acceptance Criteria

1. `mvn -pl rag -am compile` 通过
2. `mvn -pl rag -Dtest=RerankingRetrieverTest,KnowledgeBaseServiceImplTest test` 全绿
3. `mvn -pl server -am compile` 通过（两个 controller + PostgresDataSourceConfig）
4. 已有测试不被破坏
5. Rerank 失败自动降级为纯向量结果（不抛异常）
6. `BM25Retriever.retrieve()` 返回空列表，不影响主链路

## Dependencies

- C1 的所有类（Ingest pipeline、domain entities）✓
- `ModelGateway.rerank` 已在 B4 实现 ✓
- pgvector JDBC driver 已在 C1 `rag/pom.xml` 引入 ✓
- `spring-boot-starter-webflux` 已在 B1 引入（WebClient），此处用 `JdbcTemplate` 同步即可 ✓

## 设计决策

| 决策 | 理由 |
|------|------|
| RerankingRetriever 是装饰器 | 职责分离：向量检索和重排各自独立测试 |
| Rerank 失败降级不抛异常 | Rerank 服务可能不稳定，检索结果仍可用 |
| pgvector 用独立 JdbcTemplate | 主 DataSource 是 MySQL，pgvector 必须走 PG 连接 |
| BM25 留 no-op 接口 | 不删除结构，方便后续直接实现 hybrid 检索 |
| 文件上传走 multipart | 大文件不序列化为 JSON，节省内存和带宽 |
