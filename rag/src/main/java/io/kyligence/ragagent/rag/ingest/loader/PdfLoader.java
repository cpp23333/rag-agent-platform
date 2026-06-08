package io.kyligence.ragagent.rag.ingest.loader;

import io.kyligence.ragagent.rag.ingest.*;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

@Component
public class PdfLoader implements DocumentLoader {

    @Override
    public boolean supports(String sourceType) {
        return "PDF".equalsIgnoreCase(sourceType);
    }

    @Override
    public RawDocument load(IngestRequest req) {
        try (PDDocument doc = Loader.loadPDF(req.content())) {
            PDFTextStripper stripper = new PDFTextStripper();
            List<RawDocument.RawSegment> segments = new ArrayList<>();
            for (int i = 1; i <= doc.getNumberOfPages(); i++) {
                stripper.setStartPage(i);
                stripper.setEndPage(i);
                String pageText = stripper.getText(doc).strip();
                if (!pageText.isBlank()) {
                    segments.add(new RawDocument.RawSegment(
                        pageText, "text", Map.of("page", i)));
                }
            }
            String hash = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(req.content()));
            return new RawDocument(req.source(), hash, segments);
        } catch (Exception e) {
            throw new io.kyligence.ragagent.shared.exception.PlatformException(
                io.kyligence.ragagent.shared.exception.ErrorCode.INVALID_REQUEST,
                "Failed to parse PDF: " + e.getMessage(), e);
        }
    }
}
