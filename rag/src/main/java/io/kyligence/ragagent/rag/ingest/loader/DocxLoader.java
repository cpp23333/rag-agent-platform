package io.kyligence.ragagent.rag.ingest.loader;

import io.kyligence.ragagent.rag.ingest.*;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

@Component
public class DocxLoader implements DocumentLoader {

    @Override
    public boolean supports(String sourceType) {
        return "DOCX".equalsIgnoreCase(sourceType);
    }

    @Override
    public RawDocument load(IngestRequest req) {
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(req.content()))) {
            String text = new XWPFWordExtractor(doc).getText();
            String hash = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(req.content()));
            return new RawDocument(req.source(), hash,
                List.of(new RawDocument.RawSegment(text, "text", Map.of())));
        } catch (Exception e) {
            throw new io.kyligence.ragagent.shared.exception.PlatformException(
                io.kyligence.ragagent.shared.exception.ErrorCode.INVALID_REQUEST,
                "Failed to parse DOCX: " + e.getMessage(), e);
        }
    }
}
