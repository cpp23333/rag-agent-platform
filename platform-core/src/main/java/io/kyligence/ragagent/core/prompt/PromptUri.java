package io.kyligence.ragagent.core.prompt;

public record PromptUri(String name, String version) {

    public static final String LATEST = "latest";

    public static PromptUri parse(String uri) {
        if (uri == null) throw new IllegalArgumentException("uri must not be null");
        String body = uri.startsWith("prompt://") ? uri.substring(9) : uri;
        int at = body.lastIndexOf('@');
        if (at < 0) return new PromptUri(body, LATEST);
        return new PromptUri(body.substring(0, at), body.substring(at + 1));
    }

    public boolean isLatest() {
        return LATEST.equals(version);
    }
}
