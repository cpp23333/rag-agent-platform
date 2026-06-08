package io.kyligence.ragagent.rag.ingest;

public record IngestRequest(
    String kbId,
    String source,          // file path, URL, or inline text
    String sourceType,      // PDF, MARKDOWN, TXT, DOCX, HTML, URL
    byte[] content          // null for URL (fetched by loader)
) {}
