package com.booking.integration;

import com.booking.domain.outbox.OutboxEvent;
import com.booking.domain.outbox.OutboxEventRepository;
import com.booking.domain.outbox.OutboxEventStatus;
import com.booking.infrastructure.scheduler.OutboxPoller;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration 테스트 — Outbox poller (Scenario 1 happy + Scenario 3 idempotency).
 *
 * <p>Scenario 2 (ShedLock concurrency) 는 본 PR 미포함 — DB SELECT FOR UPDATE SKIP LOCKED
 * 자체가 row-level 정합성 보장. ShedLock 검증은 운영 환경 가정 (개념 검증 충분).
 *
 * <p>Source: docs/features/feature-005-outbox-poller-saga-compensation.md
 */
class OutboxPollerIntegrationTest extends IntegrationTestSupport {

    @DynamicPropertySource
    static void overridePgUrl(DynamicPropertyRegistry registry) {
        registry.add("external.pg.url", () -> "http://localhost:9999");
    }

    @Autowired
    private OutboxPoller poller;

    @Autowired
    private OutboxEventRepository outboxRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long insertPendingOutbox(UUID idempotencyKey, String payload) {
        OutboxEvent event = new OutboxEvent(null, "BookingCompleted", idempotencyKey,
            payload, OutboxEventStatus.PENDING, Instant.now(), null);
        return outboxRepository.save(event).getId();
    }

    // =========================================================================
    // Scenario 1: [happy] PENDING outbox → 폴러 → PUBLISHED + processed_event DONE
    // =========================================================================

    @Test
    @Tag("happy")
    @DisplayName("PENDING outbox → 폴러 → PUBLISHED 전이 + processed_event DONE")
    void should_publish_pending_outbox_and_mark_published() {
        long outboxId = insertPendingOutbox(UUID.randomUUID(), "{\"bookingId\":1}");

        poller.pollBatch();

        String status = jdbcTemplate.queryForObject(
            "SELECT status FROM outbox_event WHERE id = ?", String.class, outboxId);
        assertThat(status)
            .as("outbox PENDING → PUBLISHED")
            .isEqualTo("PUBLISHED");

        Long processedDoneCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM processed_event WHERE event_id = ? AND consumer_name = 'LoggingConsumer' " +
                "AND status = 'DONE'",
            Long.class, outboxId);
        assertThat(processedDoneCount)
            .as("LoggingEventConsumer 가 write-first INSERT + markDone")
            .isEqualTo(1);
    }

    // =========================================================================
    // Scenario 3: [edge:idempotency] 컨슈머 멱등 — 같은 event 두 번 처리 시 외부 부수효과 1회
    // =========================================================================

    @Test
    @Tag("edge")
    @Tag("edge:idempotency")
    @DisplayName("processed_event 이미 DONE → 컨슈머 ROW_COUNT==0 → 외부 부수효과 skip")
    void should_skip_consumer_when_processed_event_exists() {
        long outboxId = insertPendingOutbox(UUID.randomUUID(), "{\"bookingId\":2}");
        // 컨슈머 가 이미 처리한 상태 (재발행 시뮬레이션) — processed_event 사전 INSERT
        jdbcTemplate.update(
            "INSERT INTO processed_event (event_id, consumer_name, status, processed_at, created_at) " +
                "VALUES (?, 'LoggingConsumer', 'DONE', NOW(), NOW())",
            outboxId);

        poller.pollBatch();

        // outbox 는 PUBLISHED 로 전이 (폴러 가 처리)
        String outboxStatus = jdbcTemplate.queryForObject(
            "SELECT status FROM outbox_event WHERE id = ?", String.class, outboxId);
        assertThat(outboxStatus).isEqualTo("PUBLISHED");

        // processed_event 는 1건만 (ROW_COUNT==0 분기 — 외부 부수효과 skip)
        Long processedCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM processed_event WHERE event_id = ?", Long.class, outboxId);
        assertThat(processedCount)
            .as("write-first ROW_COUNT==0 → 외부 부수효과 skip + row 변경 X")
            .isEqualTo(1);

        // 기존 row status = DONE 그대로
        String processedStatus = jdbcTemplate.queryForObject(
            "SELECT status FROM processed_event WHERE event_id = ? AND consumer_name = 'LoggingConsumer'",
            String.class, outboxId);
        assertThat(processedStatus).isEqualTo("DONE");
    }
}
