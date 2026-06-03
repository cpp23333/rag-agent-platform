package io.kyligence.ragagent.core.event;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.LocalDateTime;

@Slf4j
@Component
@RequiredArgsConstructor
public class EventConsumer {

    private final PlatformEventMapper mapper;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRunStarted(RunStartedEvent event) {
        log.info("Run started: runId={}, type={}", event.runId(), event.runType());
        markProcessed(event.runId(), EventType.RUN_STARTED);
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRunFinished(RunFinishedEvent event) {
        log.info("Run finished: runId={}, status={}", event.runId(), event.status());
        markProcessed(event.runId(), EventType.RUN_FINISHED);
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onIngestCompleted(IngestJobCompletedEvent event) {
        log.info("Ingest job completed: jobId={}, success={}", event.jobId(), event.success());
        markProcessed(event.jobId(), EventType.INGEST_JOB_COMPLETED);
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onToolCallback(ToolCallbackReceivedEvent event) {
        log.info("Tool callback received: runId={}, toolCallId={}",
            event.runId(), event.toolCallId());
        markProcessed(event.runId(), EventType.TOOL_CALLBACK_RECEIVED);
    }

    private void markProcessed(String aggregateId, EventType type) {
        PlatformEvent event = mapper.selectOne(
            Wrappers.<PlatformEvent>lambdaQuery()
                .eq(PlatformEvent::getAggregateId, aggregateId)
                .eq(PlatformEvent::getEventType, type)
                .eq(PlatformEvent::getStatus, "PENDING")
                .orderByDesc(PlatformEvent::getCreatedAt)
                .last("LIMIT 1"));
        if (event != null) {
            event.setStatus("PROCESSED");
            event.setProcessedAt(LocalDateTime.now());
            mapper.updateById(event);
        }
    }
}
