package io.kyligence.ragagent.core.prompt;

import io.kyligence.ragagent.shared.exception.PlatformException;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class PromptRendererTest {

    private final PromptRenderer renderer = new PromptRenderer();

    @Test
    void rendersSimpleTemplate() {
        String result = renderer.render("Hello, {{ name }}!", Map.of("name", "World"));
        assertThat(result).isEqualTo("Hello, World!");
    }

    @Test
    void rendersConditional() {
        String tpl = "{% if formal %}Dear {{ name }}{% else %}Hi {{ name }}{% endif %}";
        assertThat(renderer.render(tpl, Map.of("name", "Alice", "formal", true)))
                .isEqualTo("Dear Alice");
    }

    @Test
    void rendersWithEmptyVariables() {
        assertThat(renderer.render("static text", Map.of())).isEqualTo("static text");
    }

    @Test
    void throwsOnBadTemplate() {
        assertThatThrownBy(() -> renderer.render("{% if unclosed", Map.of()))
                .isInstanceOf(PlatformException.class);
    }
}
