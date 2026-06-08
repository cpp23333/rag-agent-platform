package io.kyligence.ragagent.rag.retrieve;

public record RetrieveOptions(
        boolean rerank,
        String rerankModel,
        int vectorTopK
) {
    public static RetrieveOptions defaults() {
        return new RetrieveOptions(true, null, 20);
    }
}
