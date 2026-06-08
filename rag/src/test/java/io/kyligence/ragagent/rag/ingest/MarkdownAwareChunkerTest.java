package io.kyligence.ragagent.rag.ingest;

import io.kyligence.ragagent.rag.ingest.chunker.MarkdownAwareChunker;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class MarkdownAwareChunkerTest {

    private final MarkdownAwareChunker chunker = new MarkdownAwareChunker();

    @Test
    void shortTextWithNoHeadingsReturnsOneChunk() {
        List<RawChunk> chunks = chunker.chunk("Hello world", "text", Map.of());
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).text()).isEqualTo("Hello world");
    }

    @Test
    void splitsOnMarkdownHeadings() {
        String md = "# Title\n\nIntro text.\n\n## Section One\n\nContent one.\n\n## Section Two\n\nContent two.";
        List<RawChunk> chunks = chunker.chunk(md, "text", Map.of());
        assertThat(chunks.size()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void headingPathStoredInMetadata() {
        String md = "## My Section\n\nSome content here.";
        List<RawChunk> chunks = chunker.chunk(md, "text", Map.of());
        assertThat(chunks).isNotEmpty();
        assertThat(chunks.get(0).metadata()).containsKey("heading_path");
        assertThat(chunks.get(0).metadata().get("heading_path")).isEqualTo("My Section");
    }

    @Test
    void positionIsMonotonicallyIncreasing() {
        String md = "# H1\n\n" + "word ".repeat(50) + "\n\n## H2\n\n" + "word ".repeat(50);
        List<RawChunk> chunks = chunker.chunk(md, "text", Map.of());
        for (int i = 1; i < chunks.size(); i++) {
            assertThat(chunks.get(i).position()).isGreaterThan(chunks.get(i - 1).position());
        }
    }
}
