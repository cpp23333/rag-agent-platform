package io.kyligence.ragagent.rag.ingest.loader;

import io.kyligence.ragagent.rag.ingest.*;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class MarkdownTxtLoader implements DocumentLoader {

    private static final Set<String> TYPES = Set.of("MARKDOWN", "TXT", "MD");

    @Override
    public boolean supports(String sourceType) {
        return TYPES.contains(sourceType.toUpperCase());
    }

    @Override
    public RawDocument load(IngestRequest req) {
        String text = new String(req.content(), StandardCharsets.UTF_8);
        String hash = sha256(req.content());
        return new RawDocument(req.source(), hash,
            List.of(new RawDocument.RawSegment(text, "text", Map.of())));
    }

    private String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(data));
        } catch (Exception e) { throw new RuntimeException(e); }
    }
}
