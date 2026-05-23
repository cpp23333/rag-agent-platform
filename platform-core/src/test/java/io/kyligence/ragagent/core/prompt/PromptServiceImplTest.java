package io.kyligence.ragagent.core.prompt;

import io.kyligence.ragagent.shared.exception.PlatformException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PromptServiceImplTest {

    @Mock private PromptMapper promptMapper;
    private PromptServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new PromptServiceImpl(promptMapper, new PromptRenderer());
    }

    @Test
    void saveCreatesNewPrompt() {
        when(promptMapper.selectOne(any())).thenReturn(null);
        when(promptMapper.insert(any())).thenReturn(1);
        Prompt p = service.save(1L, new PromptService.SavePromptCommand(
                "qa_system", "v1", "Answer: {{ answer }}", null, null));
        assertThat(p.getName()).isEqualTo("qa_system");
        verify(promptMapper).insert(any(Prompt.class));
    }

    @Test
    void saveUpdatesExisting() {
        Prompt existing = new Prompt();
        existing.setId("id"); existing.setTemplate("old");
        when(promptMapper.selectOne(any())).thenReturn(existing);
        when(promptMapper.updateById(any())).thenReturn(1);
        Prompt result = service.save(1L, new PromptService.SavePromptCommand(
                "qa", "v1", "new", null, null));
        assertThat(result.getTemplate()).isEqualTo("new");
        verify(promptMapper, never()).insert(any());
    }

    @Test
    void renderReturnsRenderedString() {
        Prompt p = new Prompt(); p.setTemplate("Hello, {{ name }}!");
        when(promptMapper.selectOne(any())).thenReturn(p);
        assertThat(service.render(1L, "g", "v1", Map.of("name", "Bob")))
                .isEqualTo("Hello, Bob!");
    }

    @Test
    void renderThrowsWhenNotFound() {
        when(promptMapper.selectOne(any())).thenReturn(null);
        assertThatThrownBy(() -> service.render(1L, "x", "v1", Map.of()))
                .isInstanceOf(PlatformException.class);
    }
}
