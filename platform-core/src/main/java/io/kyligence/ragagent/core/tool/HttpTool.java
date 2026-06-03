package io.kyligence.ragagent.core.tool;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.kyligence.ragagent.shared.exception.ErrorCode;
import io.kyligence.ragagent.shared.exception.PlatformException;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

public class HttpTool implements Tool {

    private final ToolDef def;
    private final RestTemplate rest;
    private final ObjectMapper objectMapper;

    public HttpTool(ToolDef def, RestTemplate rest, ObjectMapper objectMapper) {
        this.def = def;
        this.rest = rest;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() { return def.getName(); }

    @Override
    public String description() { return def.getDescription(); }

    @Override
    public String inputSchemaJson() { return def.getInputSchemaJson(); }

    @Override
    public ToolResult invoke(ToolInput input, ToolContext ctx) {
        try {
            Map<String, Object> config = objectMapper.readValue(
                def.getConfigJson(), new TypeReference<>() {});
            String url = (String) config.get("url");
            String method = ((String) config.getOrDefault("method", "POST")).toUpperCase();

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            @SuppressWarnings("unchecked")
            Map<String, String> extraHeaders = (Map<String, String>)
                config.getOrDefault("headers", Map.of());
            extraHeaders.forEach(headers::set);

            String body = objectMapper.writeValueAsString(input.params());
            HttpEntity<String> entity = new HttpEntity<>(body, headers);

            ResponseEntity<String> resp = rest.exchange(url,
                HttpMethod.valueOf(method), entity, String.class);

            return ToolResult.ok(resp.getBody());
        } catch (Exception e) {
            throw new PlatformException(ErrorCode.TOOL_INVOKE_FAILED,
                "HTTP tool invocation failed: " + e.getMessage(), e);
        }
    }
}
