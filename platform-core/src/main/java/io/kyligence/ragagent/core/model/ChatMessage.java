package io.kyligence.ragagent.core.model;

public record ChatMessage(String role, String content, String name) {
    public static ChatMessage system(String content) {
        return new ChatMessage("system", content, null);
    }

    public static ChatMessage user(String content) {
        return new ChatMessage("user", content, null);
    }

    public static ChatMessage assistant(String content) {
        return new ChatMessage("assistant", content, null);
    }

    public static ChatMessage tool(String content, String name) {
        return new ChatMessage("tool", content, name);
    }
}
