package io.kyligence.ragagent.rag.ingest;

import io.kyligence.ragagent.core.model.ChatMessage;
import io.kyligence.ragagent.core.model.ChatRequest;
import io.kyligence.ragagent.core.model.ModelGateway;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.*;

/**
 * Extracts image/table content from PDFs using VLM caption.
 * Only invoked when sourceType=PDF and content is non-null.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MultiModalExtractor {

    private final ModelGateway modelGateway;

    /**
     * For each PDF page, render as image and ask VLM if there are tables/figures.
     * Returns additional RawSegment list to append to the document.
     */
    public List<RawDocument.RawSegment> extractFromPdf(byte[] pdfBytes) {
        List<RawDocument.RawSegment> extras = new ArrayList<>();
        try (PDDocument pdf = Loader.loadPDF(pdfBytes)) {
            PDFRenderer renderer = new PDFRenderer(pdf);
            for (int i = 0; i < pdf.getNumberOfPages(); i++) {
                BufferedImage img = renderer.renderImageWithDPI(i, 150, ImageType.RGB);
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                ImageIO.write(img, "PNG", baos);
                String base64 = Base64.getEncoder().encodeToString(baos.toByteArray());

                String caption = captionWithVlm(base64, i + 1);
                if (caption != null && !caption.isBlank()) {
                    extras.add(new RawDocument.RawSegment(
                        caption, "image", Map.of("page", i + 1, "type", "vlm_caption")));
                }
            }
        } catch (Exception e) {
            log.warn("Multi-modal extraction failed, skipping: {}", e.getMessage());
        }
        return extras;
    }

    private String captionWithVlm(String base64Png, int page) {
        try {
            String prompt = String.format(
                "This is page %d of a PDF document. " +
                "If this page contains tables or figures, describe their content in detail. " +
                "If the page is plain text, respond with empty string.", page);
            ChatRequest req = new ChatRequest(null, List.of(
                ChatMessage.user(prompt + "\n[IMAGE_BASE64:" + base64Png.substring(0, 20) + "...]")
            ));
            return modelGateway.chat(req).content();
        } catch (Exception e) {
            log.warn("VLM caption failed for page {}: {}", page, e.getMessage());
            return null;
        }
    }
}
