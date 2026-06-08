package io.kyligence.ragagent.rag.ingest.loader;

import io.kyligence.ragagent.rag.ingest.*;
import org.jsoup.Jsoup;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

@Component
public class UrlLoader implements DocumentLoader {

    @Override
    public boolean supports(String sourceType) {
        return "URL".equalsIgnoreCase(sourceType);
    }

    @Override
    public RawDocument load(IngestRequest req) {
        try {
            String text = Jsoup.connect(req.source())
                .timeout(15_000)
                .get()
                .body().text();
            byte[] bytes = text.getBytes();
            String hash = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes));
            return new RawDocument(req.source(), hash,
                List.of(new RawDocument.RawSegment(text, "text",
                    Map.of("url", req.source()))));
        } catch (Exception e) {
            throw new io.kyligence.ragagent.shared.exception.PlatformException(
                io.kyligence.ragagent.shared.exception.ErrorCode.INVALID_REQUEST,
                "Failed to fetch URL: " + e.getMessage(), e);
        }
    }
}
