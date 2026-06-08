package io.kyligence.ragagent.rag.ingest.chunker;

import io.kyligence.ragagent.rag.ingest.*;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Splits on Markdown headings (##, ###), preserving heading path in metadata.
 * Falls back to RecursiveChunker for oversized sections.
 */
public class MarkdownAwareChunker implements Chunker {

    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.+)$", Pattern.MULTILINE);
    private final RecursiveChunker fallback = new RecursiveChunker();

    @Override
    public List<RawChunk> chunk(String text, String modality, Map<String, Object> metadata) {
        List<String> sections = splitByHeadings(text);
        List<RawChunk> result = new ArrayList<>();
        int pos = 0;
        for (String section : sections) {
            String heading = extractHeading(section);
            Map<String, Object> meta = new HashMap<>(metadata);
            if (heading != null) meta.put("heading_path", heading);
            List<RawChunk> sub = fallback.chunk(section, modality, meta);
            for (RawChunk c : sub) {
                result.add(new RawChunk(c.text(), pos++, c.modality(), c.metadata()));
            }
        }
        return result;
    }

    private List<String> splitByHeadings(String text) {
        List<String> parts = new ArrayList<>();
        int last = 0;
        var matcher = HEADING.matcher(text);
        while (matcher.find()) {
            if (matcher.start() > last) parts.add(text.substring(last, matcher.start()));
            last = matcher.start();
        }
        if (last < text.length()) parts.add(text.substring(last));
        return parts.isEmpty() ? List.of(text) : parts;
    }

    private String extractHeading(String section) {
        var m = HEADING.matcher(section);
        return m.find() ? m.group(2) : null;
    }
}
