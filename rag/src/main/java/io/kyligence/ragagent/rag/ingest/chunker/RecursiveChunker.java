package io.kyligence.ragagent.rag.ingest.chunker;

import io.kyligence.ragagent.rag.ingest.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Recursive character-level chunker.
 * Splits by \n\n → \n → sentence-ending punctuation.
 * Default: size=500, overlap=80.
 */
public class RecursiveChunker implements Chunker {

    private static final List<String> SEPARATORS = List.of("\n\n", "\n", "。", "！", "？", ". ", "! ", "? ");

    private final int chunkSize;
    private final int overlap;

    public RecursiveChunker(int chunkSize, int overlap) {
        this.chunkSize = chunkSize;
        this.overlap = overlap;
    }

    public RecursiveChunker() { this(500, 80); }

    @Override
    public List<RawChunk> chunk(String text, String modality, Map<String, Object> metadata) {
        List<String> splits = split(text.strip());
        List<RawChunk> result = new ArrayList<>();
        int pos = 0;
        for (String s : splits) {
            result.add(new RawChunk(s, pos++, modality, metadata));
        }
        return result;
    }

    private List<String> split(String text) {
        if (text.length() <= chunkSize) return List.of(text);
        for (String sep : SEPARATORS) {
            int idx = text.lastIndexOf(sep, chunkSize);
            if (idx > 0) {
                String head = text.substring(0, idx + sep.length()).strip();
                String tail = text.substring(Math.max(0, idx + sep.length() - overlap)).strip();
                List<String> result = new ArrayList<>();
                result.add(head);
                result.addAll(split(tail));
                return result;
            }
        }
        // hard split
        List<String> result = new ArrayList<>();
        for (int i = 0; i < text.length(); i += chunkSize - overlap) {
            result.add(text.substring(i, Math.min(i + chunkSize, text.length())));
        }
        return result;
    }
}
