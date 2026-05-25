package io.kyligence.ragagent.core.memory;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class WorkingMemoryImpl implements WorkingMemory {
    private final WorkingMemoryEntryMapper mapper;

    @Override
    @Transactional
    public void put(String runId, String key, String value) {
        // Input validation
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId cannot be null or empty");
        }
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("key cannot be null or empty");
        }
        if (value == null) {
            throw new IllegalArgumentException("value cannot be null");
        }

        WorkingMemoryEntry existing = mapper.selectOne(
            Wrappers.<WorkingMemoryEntry>lambdaQuery()
                .eq(WorkingMemoryEntry::getRunId, runId)
                .eq(WorkingMemoryEntry::getEntryKey, key)
        );

        if (existing != null) {
            existing.setValueJson(value);
            mapper.updateById(existing);
        } else {
            WorkingMemoryEntry entry = new WorkingMemoryEntry();
            entry.setRunId(runId);
            entry.setEntryKey(key);
            entry.setValueJson(value);
            mapper.insert(entry);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> get(String runId, String key) {
        // Input validation
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId cannot be null or empty");
        }
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("key cannot be null or empty");
        }

        WorkingMemoryEntry entry = mapper.selectOne(
            Wrappers.<WorkingMemoryEntry>lambdaQuery()
                .eq(WorkingMemoryEntry::getRunId, runId)
                .eq(WorkingMemoryEntry::getEntryKey, key)
        );
        return Optional.ofNullable(entry)
            .map(WorkingMemoryEntry::getValueJson);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, String> getAll(String runId) {
        // Input validation
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId cannot be null or empty");
        }

        return mapper.selectList(
            Wrappers.<WorkingMemoryEntry>lambdaQuery()
                .eq(WorkingMemoryEntry::getRunId, runId)
        ).stream()
            .collect(Collectors.toMap(
                WorkingMemoryEntry::getEntryKey,
                WorkingMemoryEntry::getValueJson
            ));
    }

    @Override
    @Transactional
    public void remove(String runId, String key) {
        // Input validation
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId cannot be null or empty");
        }
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("key cannot be null or empty");
        }

        mapper.delete(
            Wrappers.<WorkingMemoryEntry>lambdaQuery()
                .eq(WorkingMemoryEntry::getRunId, runId)
                .eq(WorkingMemoryEntry::getEntryKey, key)
        );
    }

    @Override
    @Transactional
    public void clear(String runId) {
        // Input validation
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId cannot be null or empty");
        }

        mapper.delete(
            Wrappers.<WorkingMemoryEntry>lambdaQuery()
                .eq(WorkingMemoryEntry::getRunId, runId)
        );
    }
}

