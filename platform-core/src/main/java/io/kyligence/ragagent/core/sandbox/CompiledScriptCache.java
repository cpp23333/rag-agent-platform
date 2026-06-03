package io.kyligence.ragagent.core.sandbox;

import org.codehaus.janino.ScriptEvaluator;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.ConcurrentHashMap;

public class CompiledScriptCache {

    private final ConcurrentHashMap<String, ScriptEvaluator> cache = new ConcurrentHashMap<>();

    public ScriptEvaluator get(String source, ScriptEvaluatorFactory factory) throws Exception {
        String key = sha256(source);
        ScriptEvaluator cached = cache.get(key);
        if (cached != null) return cached;
        ScriptEvaluator compiled = factory.compile(source);
        cache.put(key, compiled);
        return compiled;
    }

    private String sha256(String input) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : hash) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    @FunctionalInterface
    public interface ScriptEvaluatorFactory {
        ScriptEvaluator compile(String source) throws Exception;
    }
}
