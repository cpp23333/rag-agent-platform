package io.kyligence.ragagent.rag.ingest;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class DocumentCleanerTest {

    private final DocumentCleaner cleaner = new DocumentCleaner();

    @Test
    void collapsesExcessiveWhitespace() {
        RawDocument doc = new RawDocument("src", "hash",
            List.of(new RawDocument.RawSegment("hello    world", "text", Map.of())));
        RawDocument cleaned = cleaner.clean(doc);
        assertThat(cleaned.segments().get(0).text()).isEqualTo("hello world");
    }

    @Test
    void removesBlankSegments() {
        RawDocument doc = new RawDocument("src", "hash", List.of(
            new RawDocument.RawSegment("  ", "text", Map.of()),
            new RawDocument.RawSegment("content", "text", Map.of())));
        assertThat(cleaner.clean(doc).segments()).hasSize(1);
    }

    @Test
    void collapsesExcessiveNewlines() {
        RawDocument doc = new RawDocument("src", "hash",
            List.of(new RawDocument.RawSegment("a\n\n\n\n\nb", "text", Map.of())));
        assertThat(cleaner.clean(doc).segments().get(0).text()).isEqualTo("a\n\nb");
    }
}
