package io.kyligence.ragagent.rag.ingest.loader;

import io.kyligence.ragagent.rag.ingest.*;
import org.jsoup.Jsoup;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class HtmlLoader implements DocumentLoader {

    private static final Set<String> TYPES = Set.of("HTML", "HTM");

    @Override
    public boolean supports(String sourceType) {
        return TYPES.contains(sourceType.toUpperCase());
    }

    @Override
    public RawDocument load(IngestRequest req) {
        String html = new String(req.content(), StandardCharsets.UTF_8);
        String text = Jsoup.parse(html).body().text();
        String hash = HexFormat.of().formatHex(hashOf(req.content()));
        return new RawDocument(req.source(), hash,
            List.of(new RawDocument.RawSegment(text, "text", Map.of())));
    }

    private byte[] hashOf(byte[] data) {
        try { return MessageDigest.getInstance("SHA-256").digest(data); }
        catch (Exception e) { throw new RuntimeException(e); }
    }
}
