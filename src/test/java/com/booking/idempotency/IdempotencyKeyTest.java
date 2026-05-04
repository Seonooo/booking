package com.booking.idempotency;

import com.booking.domain.idempotency.IdempotencyKey;
import com.booking.domain.idempotency.IdempotencyStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link IdempotencyKey} aggregate (ADR-006, ERD §4.6).
 *
 * <p>Source: docs/features/feature-001-idempotency-handling.md §Phase 1 §1
 * Data model sketch + §Phase 3.1 Domain layer GREEN.
 */
class IdempotencyKeyTest {

    private static final UUID KEY = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
    private static final long USER_ID = 1001L;
    private static final String HASH = "a".repeat(64);
    private static final Instant CREATED = Instant.parse("2026-05-04T12:00:00Z");
    private static final Instant EXPIRES = CREATED.plus(15, ChronoUnit.MINUTES);

    private static IdempotencyKey aProcessingKey() {
        return new IdempotencyKey(KEY, USER_ID, HASH,
            IdempotencyStatus.PROCESSING, null, null, CREATED, EXPIRES);
    }

    private static IdempotencyKey aCompletedKey(String responsePayload, long bookingId) {
        return new IdempotencyKey(KEY, USER_ID, HASH,
            IdempotencyStatus.COMPLETED, responsePayload, bookingId, CREATED, EXPIRES);
    }

    @Nested
    @DisplayName("constructor — null 검증")
    class ConstructorInvariants {

        @Test
        @DisplayName("idempotencyKey null → NPE")
        void should_throw_NPE_when_idempotencyKey_is_null() {
            assertThatNullPointerException()
                .isThrownBy(() -> new IdempotencyKey(null, USER_ID, HASH,
                    IdempotencyStatus.PROCESSING, null, null, CREATED, EXPIRES))
                .withMessageContaining("idempotencyKey");
        }

        @Test
        @DisplayName("bodyHash null → NPE")
        void should_throw_NPE_when_bodyHash_is_null() {
            assertThatNullPointerException()
                .isThrownBy(() -> new IdempotencyKey(KEY, USER_ID, null,
                    IdempotencyStatus.PROCESSING, null, null, CREATED, EXPIRES))
                .withMessageContaining("bodyHash");
        }

        @Test
        @DisplayName("status null → NPE")
        void should_throw_NPE_when_status_is_null() {
            assertThatNullPointerException()
                .isThrownBy(() -> new IdempotencyKey(KEY, USER_ID, HASH,
                    null, null, null, CREATED, EXPIRES))
                .withMessageContaining("status");
        }

        @Test
        @DisplayName("createdAt null → NPE")
        void should_throw_NPE_when_createdAt_is_null() {
            assertThatNullPointerException()
                .isThrownBy(() -> new IdempotencyKey(KEY, USER_ID, HASH,
                    IdempotencyStatus.PROCESSING, null, null, null, EXPIRES))
                .withMessageContaining("createdAt");
        }

        @Test
        @DisplayName("expiresAt null → NPE")
        void should_throw_NPE_when_expiresAt_is_null() {
            assertThatNullPointerException()
                .isThrownBy(() -> new IdempotencyKey(KEY, USER_ID, HASH,
                    IdempotencyStatus.PROCESSING, null, null, CREATED, null))
                .withMessageContaining("expiresAt");
        }

        @Test
        @DisplayName("PROCESSING 상태에서 responsePayload / bookingId null 허용")
        void should_allow_null_responsePayload_and_bookingId_when_PROCESSING() {
            IdempotencyKey key = aProcessingKey();
            assertThat(key.getResponsePayload()).isNull();
            assertThat(key.getBookingId()).isNull();
        }
    }

    @Nested
    @DisplayName("status — isProcessing / isCompleted")
    class StatusChecks {

        @Test
        @DisplayName("PROCESSING 상태 → isProcessing true / isCompleted false")
        void should_be_processing_when_status_is_PROCESSING() {
            IdempotencyKey key = aProcessingKey();
            assertThat(key.isProcessing()).isTrue();
            assertThat(key.isCompleted()).isFalse();
        }

        @Test
        @DisplayName("COMPLETED 상태 → isCompleted true / isProcessing false")
        void should_be_completed_when_status_is_COMPLETED() {
            IdempotencyKey key = aCompletedKey("{\"bookingId\":123}", 123L);
            assertThat(key.isCompleted()).isTrue();
            assertThat(key.isProcessing()).isFalse();
        }
    }

    @Nested
    @DisplayName("body hash 매칭")
    class BodyHashMatching {

        @Test
        @DisplayName("저장된 hash 와 입력 hash 일치 → true")
        void should_match_body_hash_when_hash_equals() {
            IdempotencyKey key = aProcessingKey();
            assertThat(key.isBodyHashMatching(HASH)).isTrue();
        }

        @Test
        @DisplayName("저장된 hash 와 입력 hash 불일치 → false")
        void should_not_match_body_hash_when_hash_differs() {
            IdempotencyKey key = aProcessingKey();
            assertThat(key.isBodyHashMatching("b".repeat(64))).isFalse();
        }
    }

    @Nested
    @DisplayName("TTL 만료")
    class ExpirationCheck {

        @Test
        @DisplayName("now > expiresAt → expired")
        void should_be_expired_when_now_is_after_expiresAt() {
            IdempotencyKey key = aProcessingKey();
            Instant after = EXPIRES.plus(1, ChronoUnit.SECONDS);
            assertThat(key.isExpired(after)).isTrue();
        }

        @Test
        @DisplayName("now <= expiresAt → not expired")
        void should_not_be_expired_when_now_is_before_or_equal_expiresAt() {
            IdempotencyKey key = aProcessingKey();
            assertThat(key.isExpired(EXPIRES)).isFalse();
            assertThat(key.isExpired(EXPIRES.minus(1, ChronoUnit.SECONDS))).isFalse();
        }

        @Test
        @DisplayName("now null → NPE")
        void should_throw_NPE_when_now_is_null() {
            IdempotencyKey key = aProcessingKey();
            assertThatNullPointerException()
                .isThrownBy(() -> key.isExpired(null))
                .withMessageContaining("now");
        }
    }

    @Nested
    @DisplayName("complete — 상태 전이")
    class CompleteTransition {

        @Test
        @DisplayName("PROCESSING → COMPLETED 새 인스턴스 반환, 원본 보존")
        void should_return_new_instance_with_COMPLETED_status_when_complete_called() {
            IdempotencyKey original = aProcessingKey();
            String payload = "{\"bookingId\":42,\"status\":\"OK\"}";

            IdempotencyKey completed = original.complete(payload, 42L);

            assertThat(completed).isNotSameAs(original);
            assertThat(completed.isCompleted()).isTrue();
            assertThat(completed.getResponsePayload()).isEqualTo(payload);
            assertThat(completed.getBookingId()).isEqualTo(42L);
            // 원본 보존
            assertThat(original.isProcessing()).isTrue();
            assertThat(original.getResponsePayload()).isNull();
            assertThat(original.getBookingId()).isNull();
        }

        @Test
        @DisplayName("complete 후 immutable 필드 (key/userId/bodyHash/createdAt/expiresAt) 보존")
        void should_preserve_immutable_fields_after_complete() {
            IdempotencyKey original = aProcessingKey();

            IdempotencyKey completed = original.complete("{}", 99L);

            assertThat(completed.getIdempotencyKey()).isEqualTo(KEY);
            assertThat(completed.getUserId()).isEqualTo(USER_ID);
            assertThat(completed.getBodyHash()).isEqualTo(HASH);
            assertThat(completed.getCreatedAt()).isEqualTo(CREATED);
            assertThat(completed.getExpiresAt()).isEqualTo(EXPIRES);
        }

        @Test
        @DisplayName("이미 COMPLETED 상태에서 complete 호출 → IllegalStateException")
        void should_throw_when_complete_called_on_already_COMPLETED() {
            IdempotencyKey already = aCompletedKey("{}", 1L);

            assertThatThrownBy(() -> already.complete("{}", 2L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PROCESSING");
        }

        @Test
        @DisplayName("complete responsePayload null → NPE")
        void should_throw_NPE_when_responsePayload_is_null() {
            IdempotencyKey original = aProcessingKey();

            assertThatNullPointerException()
                .isThrownBy(() -> original.complete(null, 42L))
                .withMessageContaining("responsePayload");
        }
    }
}
