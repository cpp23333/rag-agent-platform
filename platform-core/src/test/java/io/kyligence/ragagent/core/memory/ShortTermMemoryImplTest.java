package io.kyligence.ragagent.core.memory;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ShortTermMemoryImplTest {

    @Mock private ShortTermMessageMapper mapper;
    private ShortTermMemoryImpl memory;

    @BeforeEach
    void setUp() {
        memory = new ShortTermMemoryImpl(mapper);
    }

    @Test
    void appendInsertsWithCorrectIdx() {
        when(mapper.selectMaxIdx("run-1")).thenReturn(2);
        when(mapper.insert(any())).thenReturn(1);

        memory.append("run-1", MessageRole.USER, "hello", null);

        ArgumentCaptor<ShortTermMessage> cap = ArgumentCaptor.forClass(ShortTermMessage.class);
        verify(mapper).insert(cap.capture());
        assertThat(cap.getValue().getIdx()).isEqualTo(3);
        assertThat(cap.getValue().getRole()).isEqualTo(MessageRole.USER);
        assertThat(cap.getValue().getContent()).isEqualTo("hello");
    }

    @Test
    void getWindowReturnsLastN() {
        ShortTermMessage m1 = new ShortTermMessage();
        m1.setIdx(9);
        ShortTermMessage m2 = new ShortTermMessage();
        m2.setIdx(8);
        ShortTermMessage m3 = new ShortTermMessage();
        m3.setIdx(7);
        when(mapper.selectList(any())).thenReturn(List.of(m1, m2, m3));

        List<ShortTermMessage> result = memory.getWindow("run-1", 3);
        assertThat(result).hasSize(3);
        assertThat(result.get(0).getIdx()).isEqualTo(7);
        assertThat(result.get(1).getIdx()).isEqualTo(8);
        assertThat(result.get(2).getIdx()).isEqualTo(9);
        verify(mapper).selectList(any(LambdaQueryWrapper.class));
    }

    @Test
    void clearDeletesByRunId() {
        when(mapper.delete(any())).thenReturn(5);
        memory.clear("run-1");
        verify(mapper).delete(any(LambdaQueryWrapper.class));
    }
}
