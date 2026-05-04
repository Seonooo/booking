-- Booking system baseline schema (ERD §8, MySQL 8.0+)
--
-- Order: FK 참조 대상 (users, accommodation) 먼저 → booking → 종속 테이블 순.
-- ENGINE / CHARSET / COLLATE: ADR-008 / CONVENTIONS-FILE.md §6 정합.

-- ============================================================
-- supporting (FK 참조 대상)
-- ============================================================

-- 회원 식별자 — 본 시스템은 인증을 외부 게이트웨이에 위임하므로
-- 본 테이블은 FK 무결성 + created_at 추적용 최소 스키마.
CREATE TABLE users (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 숙소 마스터.
CREATE TABLE accommodation (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    name        VARCHAR(200) NOT NULL,
    base_price  DECIMAL(15, 2) NOT NULL,
    created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ============================================================
-- booking (Aggregate Root, ADR-008 / ADR-009)
-- ============================================================

-- status: HOLD / PG_PENDING / COMPLETED / FAILED / UNKNOWN / REFUND_PENDING (state machine ERD §6.1).
-- payment_composition_snapshot: ADR-009 PaymentComposition VO 의 결제 구성 JSON (감사용).
-- idempotency_key: BINARY(16) UUID (ADR-006). UNIQUE 인덱스로 동일 키 중복 booking 차단.
CREATE TABLE booking (
    id                            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id                       BIGINT UNSIGNED NOT NULL,
    accommodation_id              BIGINT UNSIGNED NOT NULL,
    idempotency_key               BINARY(16) NOT NULL,
    amount                        DECIMAL(15, 2) NOT NULL,
    payment_composition_snapshot  JSON NOT NULL,
    status                        VARCHAR(20) NOT NULL,
    created_at                    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_booking_idempotency_key (idempotency_key),
    KEY idx_booking_status_updated (status, updated_at),
    KEY idx_booking_user (user_id),
    KEY idx_booking_accommodation (accommodation_id),
    CONSTRAINT fk_booking_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT fk_booking_accommodation FOREIGN KEY (accommodation_id) REFERENCES accommodation(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ============================================================
-- payment_attempt (PG idempotency 단위, ADR-011)
-- ============================================================

-- attempt_id: PG 멱등성 키 (UUID, BINARY(16)). PG 호출 시 이 키로 중복 청구 방지.
-- status: REQUESTED / SUCCESS / FAILED / TIMEOUT / UNKNOWN (state machine ERD §6.2).
-- last_requested_at / last_reconcile_at / reconcile_retry_count: ADR-011 reconciliation worker 가 사용.
CREATE TABLE payment_attempt (
    id                            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    attempt_id                    BINARY(16) NOT NULL,
    booking_id                    BIGINT UNSIGNED NOT NULL,
    amount                        DECIMAL(15, 2) NOT NULL,
    payment_composition_snapshot  JSON NOT NULL,
    status                        VARCHAR(20) NOT NULL,
    external_payment_id           VARCHAR(100) DEFAULT NULL,
    last_requested_at             TIMESTAMP NULL DEFAULT NULL,
    last_reconcile_at             TIMESTAMP NULL DEFAULT NULL,
    reconcile_retry_count         TINYINT UNSIGNED NOT NULL DEFAULT 0,
    created_at                    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_pa_attempt_id (attempt_id),
    KEY idx_pa_booking (booking_id),
    KEY idx_pa_status_updated (status, updated_at),
    KEY idx_pa_external_payment (external_payment_id),
    CONSTRAINT fk_pa_booking FOREIGN KEY (booking_id) REFERENCES booking(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ============================================================
-- cancellation_intent (취소 의향, ADR-011)
-- ============================================================

-- ADR-011 UNKNOWN 상태에서 사용자 취소 의향을 큐잉. forward path 미사용,
-- ADR-012 환불 도메인 진입 시 활성화.
-- refund_target: PG / POINT (복합 결제 시 두 row 가능).
-- status: REQUESTED / PROCESSING / DONE / FAILED (ERD §6.3).
CREATE TABLE cancellation_intent (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    booking_id    BIGINT UNSIGNED NOT NULL,
    refund_target VARCHAR(20) NOT NULL,
    status        VARCHAR(20) NOT NULL,
    retry_count   TINYINT UNSIGNED NOT NULL DEFAULT 0,
    requested_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    processed_at  TIMESTAMP NULL DEFAULT NULL,
    created_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ci_booking_target (booking_id, refund_target),
    KEY idx_ci_status (status),
    CONSTRAINT fk_ci_booking FOREIGN KEY (booking_id) REFERENCES booking(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ============================================================
-- outbox_event (Transactional Outbox, ADR-010)
-- ============================================================

-- idempotency_key: 본 outbox row 자체의 멱등성 키 (Booking idempotency_key 와 동일 값).
-- status: PENDING / PUBLISHED / FAILED — 폴러가 PENDING → PUBLISHED 갱신.
CREATE TABLE outbox_event (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    event_type      VARCHAR(50) NOT NULL,
    idempotency_key BINARY(16) NOT NULL,
    payload         JSON NOT NULL,
    status          VARCHAR(20) NOT NULL,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at    TIMESTAMP NULL DEFAULT NULL,
    PRIMARY KEY (id),
    KEY idx_outbox_status_created (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ============================================================
-- processed_event (Consumer Idempotency, ADR-010 amendment)
-- ============================================================

-- (event_id, consumer_name) 복합 PK — 같은 이벤트를 여러 consumer 가 각자 멱등 처리.
-- outbox_event(id) FK 미선언 — retention 차이로 약결합 (ERD §4.5).
CREATE TABLE processed_event (
    event_id      BIGINT UNSIGNED NOT NULL,
    consumer_name VARCHAR(50) NOT NULL,
    status        VARCHAR(20) NOT NULL,
    processed_at  TIMESTAMP NULL DEFAULT NULL,
    created_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (event_id, consumer_name),
    KEY idx_processed_event_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ============================================================
-- idempotency_key (API 멱등성 DB 계층, ADR-006)
-- ============================================================

-- Redis 1차 (Lua atomic) + DB 2차 (UNIQUE constraint) 이중 계층의 2차.
-- body_hash: SHA-256 hex 64자 (ADR-006 §5).
-- status: PROCESSING / COMPLETED.
-- response_payload: COMPLETED 후 캐시 응답 (200 cached 시 사용).
-- expires_at: createdAt + 15분 (TTL).
CREATE TABLE idempotency_key (
    idempotency_key   BINARY(16) NOT NULL,
    user_id           BIGINT UNSIGNED NOT NULL,
    body_hash         CHAR(64) NOT NULL,
    status            VARCHAR(20) NOT NULL,
    response_payload  JSON DEFAULT NULL,
    booking_id        BIGINT UNSIGNED DEFAULT NULL,
    created_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at        TIMESTAMP NOT NULL,
    PRIMARY KEY (idempotency_key),
    KEY idx_ik_expires (expires_at),
    KEY idx_ik_booking (booking_id),
    CONSTRAINT fk_ik_booking FOREIGN KEY (booking_id) REFERENCES booking(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ============================================================
-- shedlock (라이브러리 표준 — Outbox 폴러 / Reconciliation 워커 분산 락)
-- ============================================================

-- net.javacrumbs.shedlock baseline. Outbox poller (ADR-010) / Reconciliation
-- worker (ADR-011) 가 다중 인스턴스에서 중복 실행되지 않도록 분산 락 보장.
-- 본 테이블은 baseline 으로만 추가, 실제 락 사용은 해당 feature 진입 시.
CREATE TABLE shedlock (
    name       VARCHAR(64) NOT NULL,
    lock_until TIMESTAMP(3) NOT NULL,
    locked_at  TIMESTAMP(3) NOT NULL,
    locked_by  VARCHAR(255) NOT NULL,
    PRIMARY KEY (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
