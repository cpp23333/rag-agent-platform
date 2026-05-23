package io.kyligence.ragagent.core.prompt;

import io.pebbletemplates.pebble.PebbleEngine;
import io.pebbletemplates.pebble.template.PebbleTemplate;
import io.kyligence.ragagent.shared.exception.ErrorCode;
import io.kyligence.ragagent.shared.exception.PlatformException;
import org.springframework.stereotype.Component;

import java.io.StringWriter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class PromptRenderer {

    private final PebbleEngine engine;
    private final ConcurrentHashMap<String, PebbleTemplate> cache = new ConcurrentHashMap<>();

    public PromptRenderer() {
        this.engine = new PebbleEngine.Builder()
                .autoEscaping(false)
                .build();
    }

    public String render(String templateSource, Map<String, Object> variables) {
        try {
            String cacheKey = Integer.toHexString(templateSource.hashCode());
            PebbleTemplate tpl = cache.computeIfAbsent(cacheKey, k -> {
                try {
                    return engine.getLiteralTemplate(templateSource);
                } catch (Exception e) {
                    throw new PlatformException(ErrorCode.PROMPT_RENDER_FAILED,
                            "Failed to compile template: " + e.getMessage(), e);
                }
            });
            StringWriter writer = new StringWriter();
            tpl.evaluate(writer, variables);
            return writer.toString();
        } catch (PlatformException pe) {
            throw pe;
        } catch (Exception e) {
            throw new PlatformException(ErrorCode.PROMPT_RENDER_FAILED,
                    "Failed to render template: " + e.getMessage(), e);
        }
    }
}
