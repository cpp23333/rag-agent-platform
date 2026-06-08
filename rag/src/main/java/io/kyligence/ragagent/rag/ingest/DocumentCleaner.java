package io.kyligence.ragagent.rag.ingest;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
public class DocumentCleaner {

    private static final Pattern REPEATED_BLANK = Pattern.compile("[ \\t]{3,}");
    private static final Pattern REPEATED_NEWLINE = Pattern.compile("\\n{4,}");

    /** Clean segments: collapse whitespace, remove blank lines. */
    public RawDocument clean(RawDocument doc) {
        List<RawDocument.RawSegment> cleaned = doc.segments().stream()
            .map(seg -> new RawDocument.RawSegment(
                cleanText(seg.text()), seg.modality(), seg.metadata()))
            .filter(seg -> !seg.text().isBlank())
            .collect(Collectors.toList());
        return new RawDocument(doc.source(), doc.contentHash(), cleaned);
    }

    private String cleanText(String text) {
        String s = REPEATED_BLANK.matcher(text).replaceAll(" ");
        s = REPEATED_NEWLINE.matcher(s).replaceAll("\n\n");
        return s.strip();
    }
}
