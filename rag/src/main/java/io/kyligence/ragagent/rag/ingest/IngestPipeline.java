package io.kyligence.ragagent.rag.ingest;

import io.kyligence.ragagent.rag.ingest.chunker.MarkdownAwareChunker;
import io.kyligence.ragagent.rag.ingest.chunker.RecursiveChunker;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
public class IngestPipeline {

    private final List<DocumentLoader> loaders;
    private final DocumentCleaner cleaner;
    private final MultiModalExtractor multiModal;
    private final Embedder embedder;

    public record PipelineResult(RawDocument doc, List<RawChunk> chunks, List<float[]> embeddings) {}

    public PipelineResult process(IngestRequest req, String embedModel, boolean isPdf) {
        // 1. Load
        DocumentLoader loader = loaders.stream()
            .filter(l -> l.supports(req.sourceType()))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("No loader for: " + req.sourceType()));
        RawDocument raw = loader.load(req);

        // 2. Multi-modal (PDF only)
        if (isPdf && req.content() != null) {
            List<RawDocument.RawSegment> extras = multiModal.extractFromPdf(req.content());
            List<RawDocument.RawSegment> combined = new ArrayList<>(raw.segments());
            combined.addAll(extras);
            raw = new RawDocument(raw.source(), raw.contentHash(), combined);
        }

        // 3. Clean
        raw = cleaner.clean(raw);

        // 4. Chunk
        Chunker chunker = "MARKDOWN".equalsIgnoreCase(req.sourceType()) || "MD".equalsIgnoreCase(req.sourceType())
            ? new MarkdownAwareChunker()
            : new RecursiveChunker();

        List<RawChunk> chunks = new ArrayList<>();
        for (RawDocument.RawSegment seg : raw.segments()) {
            chunks.addAll(chunker.chunk(seg.text(), seg.modality(), seg.metadata()));
        }

        // 5. Embed
        List<String> texts = chunks.stream().map(RawChunk::text).toList();
        List<float[]> embeddings = embedder.embed(texts, embedModel);

        return new PipelineResult(raw, chunks, embeddings);
    }
}
