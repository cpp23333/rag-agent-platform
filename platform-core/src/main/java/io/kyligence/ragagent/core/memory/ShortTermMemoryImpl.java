package io.kyligence.ragagent.core.memory;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Collections;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ShortTermMemoryImpl implements ShortTermMemory {
    private final ShortTermMessageMapper mapper;

    @Override
    @Transactional
    public void append(String runId, MessageRole role, String content, String name) {
        // Input validation
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId cannot be null or empty");
        }
        if (role == null) {
            throw new IllegalArgumentException("role cannot be null");
        }
        if (content == null) {
            throw new IllegalArgumentException("content cannot be null");
        }

        // Use SELECT MAX(idx) + 1 FOR UPDATE to prevent race condition
        Integer maxIdx = mapper.selectMaxIdx(runId);
        int nextIdx = (maxIdx == null) ? 0 : maxIdx + 1;

        ShortTermMessage message = new ShortTermMessage();
        message.setRunId(runId);
        message.setIdx(nextIdx);
        message.setRole(role);
        message.setContent(content);
        message.setName(name);

        mapper.insert(message);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ShortTermMessage> getWindow(String runId, int windowSize) {
        // Input validation
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId cannot be null or empty");
        }
        if (windowSize < 0) {
            throw new IllegalArgumentException("windowSize cannot be negative");
        }
        if (windowSize == 0) {
            return Collections.emptyList();
        }

        // Single query: ORDER BY DESC + LIMIT, then reverse
        List<ShortTermMessage> messages = mapper.selectList(
            Wrappers.<ShortTermMessage>lambdaQuery()
                .eq(ShortTermMessage::getRunId, runId)
                .orderByDesc(ShortTermMessage::getIdx)
                .last("LIMIT " + windowSize)
        );

        // Reverse to get chronological order (create mutable copy first)
        List<ShortTermMessage> result = new java.util.ArrayList<>(messages);
        Collections.reverse(result);
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ShortTermMessage> getAll(String runId) {
        // Input validation
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId cannot be null or empty");
        }

        return mapper.selectList(
            Wrappers.<ShortTermMessage>lambdaQuery()
                .eq(ShortTermMessage::getRunId, runId)
                .orderByAsc(ShortTermMessage::getIdx)
        );
    }

    @Override
    @Transactional(readOnly = true)
    public int count(String runId) {
        // Input validation
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId cannot be null or empty");
        }

        Long count = mapper.selectCount(
            Wrappers.<ShortTermMessage>lambdaQuery()
                .eq(ShortTermMessage::getRunId, runId)
        );

        // Check for overflow
        if (count != null && count > Integer.MAX_VALUE) {
            throw new ArithmeticException("Message count exceeds Integer.MAX_VALUE: " + count);
        }

        return count != null ? count.intValue() : 0;
    }

    @Override
    @Transactional
    public void clear(String runId) {
        // Input validation
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId cannot be null or empty");
        }

        mapper.delete(
            Wrappers.<ShortTermMessage>lambdaQuery()
                .eq(ShortTermMessage::getRunId, runId)
        );
    }
}
