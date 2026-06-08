package io.kyligence.ragagent.rag.ingest;

import io.kyligence.ragagent.rag.ingest.chunker.RecursiveChunker;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class RecursiveChunkerTest {

    private final RecursiveChunker chunker = new RecursiveChunker(100, 20);

    @Test
    void shortTextReturnsOneChunk() {
        List<RawChunk> chunks = chunker.chunk("Hello world", "text", Map.of());
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).text()).isEqualTo("Hello world");
    }

    @Test
    void longTextSplitsOnParagraph() {
        String para1 = "A".repeat(60);
        String para2 = "B".repeat(60);
        List<RawChunk> chunks = chunker.chunk(para1 + "\n\n" + para2, "text", Map.of());
        assertThat(chunks.size()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void positionIsMonotonicallyIncreasing() {
        String text = ("word ").repeat(200);
        List<RawChunk> chunks = chunker.chunk(text, "text", Map.of());
        for (int i = 1; i < chunks.size(); i++) {
            assertThat(chunks.get(i).position()).isGreaterThan(chunks.get(i - 1).position());
        }
    }
}
