package io.kyligence.ragagent.core.memory;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("WorkingMemoryImpl Unit Tests")
class WorkingMemoryImplTest {

    @Mock
    private WorkingMemoryEntryMapper mapper;
    private WorkingMemoryImpl memory;

    @BeforeEach
    void setUp() {
        memory = new WorkingMemoryImpl(mapper);
    }

    @Test
    @DisplayName("put inserts new entry when key does not exist")
    void putInsertsNewEntry() {
        when(mapper.selectOne(any())).thenReturn(null);
        when(mapper.insert(any())).thenReturn(1);

        memory.put("run-1", "key1", "value1");

        ArgumentCaptor<WorkingMemoryEntry> cap = ArgumentCaptor.forClass(WorkingMemoryEntry.class);
        verify(mapper).insert(cap.capture());
        assertThat(cap.getValue().getRunId()).isEqualTo("run-1");
        assertThat(cap.getValue().getEntryKey()).isEqualTo("key1");
        assertThat(cap.getValue().getValueJson()).isEqualTo("value1");
    }

    @Test
    @DisplayName("put updates existing entry when key exists")
    void putUpdatesExistingEntry() {
        WorkingMemoryEntry existing = new WorkingMemoryEntry();
        existing.setId(1L);
        existing.setRunId("run-1");
        existing.setEntryKey("key1");
        existing.setValueJson("oldValue");

        when(mapper.selectOne(any())).thenReturn(existing);
        when(mapper.updateById(any())).thenReturn(1);

        memory.put("run-1", "key1", "newValue");

        ArgumentCaptor<WorkingMemoryEntry> cap = ArgumentCaptor.forClass(WorkingMemoryEntry.class);
        verify(mapper).updateById(cap.capture());
        assertThat(cap.getValue().getId()).isEqualTo(1L);
        assertThat(cap.getValue().getValueJson()).isEqualTo("newValue");
    }

    @Test
    @DisplayName("get returns value when key exists")
    void getReturnsValueWhenExists() {
        WorkingMemoryEntry entry = new WorkingMemoryEntry();
        entry.setValueJson("testValue");

        when(mapper.selectOne(any())).thenReturn(entry);

        Optional<String> result = memory.get("run-1", "key1");

        assertThat(result).isPresent();
        assertThat(result.get()).isEqualTo("testValue");
    }

    @Test
    @DisplayName("get returns empty when key does not exist")
    void getReturnsEmptyWhenNotExists() {
        when(mapper.selectOne(any())).thenReturn(null);

        Optional<String> result = memory.get("run-1", "key1");

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("getAll returns all entries as map")
    void getAllReturnsMap() {
        WorkingMemoryEntry e1 = new WorkingMemoryEntry();
        e1.setEntryKey("key1");
        e1.setValueJson("value1");

        WorkingMemoryEntry e2 = new WorkingMemoryEntry();
        e2.setEntryKey("key2");
        e2.setValueJson("value2");

        when(mapper.selectList(any())).thenReturn(List.of(e1, e2));

        Map<String, String> result = memory.getAll("run-1");

        assertThat(result).hasSize(2);
        assertThat(result.get("key1")).isEqualTo("value1");
        assertThat(result.get("key2")).isEqualTo("value2");
    }

    @Test
    @DisplayName("remove deletes entry by key")
    void removeDeletesEntry() {
        when(mapper.delete(any())).thenReturn(1);

        memory.remove("run-1", "key1");

        verify(mapper).delete(any(LambdaQueryWrapper.class));
    }

    @Test
    @DisplayName("clear deletes all entries for runId")
    void clearDeletesAllEntries() {
        when(mapper.delete(any())).thenReturn(5);

        memory.clear("run-1");

        verify(mapper).delete(any(LambdaQueryWrapper.class));
    }
}

