# ADR-2026-05-01-010: 이벤트 처리 전략 — Transactional Outbox + In-Process 핸들러, EventPublisher 추상화

## Status

Accepted

## Context

본 시스템은 결제 완료 후 후처리(알림, 통계, 향후 정산 등)를 비동기로 수행해야 한다. 이 과정에서 다음 두 가지 정합성 문제가 발생할 수 있다.

**문제 1. PG 호출과 DB 커밋의 atomicity**
```
T+0: PG 호출 → "결제 성공" 응답
T+1: DB Booking INSERT 시도
T+1.5: DB 장애 또는 서버 다운
결과: PG 청구됨 + booking 없음 (사용자: "돈은 빠졌는데 예약 안 됐다")
```

**문제 2. DB 커밋과 후처리 발행의 atomicity**
```
T+0: DB Booking INSERT 성공
T+1: 알림 메시지 발행 시도
T+1.5: 메시지 발행 실패 (네트워크 오류) 또는 서버 다운
결과: booking 있음 + 알림 안 감 (사용자: "예약했는데 알림 못 받음")
```

이 두 문제는 외부 결제 비즈니스의 특성상 **PG 호출 자체를 롤백할 수 없다**는 점에서 더 심각하다. 단순 *"DB 트랜잭션 롤백"*으로 해결되지 않으며, 별도 정합성 메커니즘이 필요하다. 산업에서 이 문제는 *"망취소(Network Cancellation)"* 시나리오로 알려져 있으며, 결제 시스템에서 표준 도구가 **Transactional Outbox 패턴**이다.

또한 후처리 자체에 대한 도구 선택이 필요하다. 옵션은 in-process 이벤트(Spring ApplicationEventPublisher), 메시지 브로커(Kafka, RabbitMQ), Redis Stream, 클라우드 큐(SQS) 등이다. 본 시스템의 환경(평시 50 TPS, 단일 애플리케이션, 컨슈머 1~2개)에서 어느 도구가 적정한지 결정해야 한다.

마지막으로, 본 결정은 미래 확장성과 균형을 맞춰야 한다. 현재 단순한 도구를 선택하더라도, 향후 마이크로서비스 분리 또는 fan-out 필요 시 도구 교체가 용이해야 한다.

## Options Considered

### 축 1. 정합성 메커니즘 (PG-DB-이벤트 atomicity)

| 옵션 | Pros | Cons |
|---|---|---|
| A. 직접 발행 (Outbox 없음) | 단순, 즉시 발행 | DB 커밋 후 발행 실패 시 이벤트 영구 유실, 망취소 시나리오 무방어 |
| **B. Transactional Outbox** | DB-이벤트 atomicity 보장, 재발송 가능, 산업 표준 | 폴링/CDC 메커니즘 추가 필요 |
| C. 분산 트랜잭션 (XA) | 강한 정합성 | 외부 시스템과의 분산 트랜잭션 사실상 사장됨, 성능 저하 |

### 축 2. 폴링 vs CDC

| 옵션 | Pros | Cons |
|---|---|---|
| **A. @Scheduled 폴링** | 단순, 인프라 추가 없음, Spring 표준 | 폴링 주기만큼 지연, DB 폴링 부하 |
| B. CDC (Debezium) | 실시간, 폴링 부하 없음 | 인프라 추가 (Kafka Connect 등), 운영 복잡도 ↑ |
| C. 트랜잭션 후 즉시 비동기 발행 + 폴링 폴백 | 빠른 발행 + 안정성 | 두 메커니즘 동시 운영 복잡도 |

### 축 3. 이벤트 전달 도구

| 옵션 | Pros | Cons |
|---|---|---|
| **A. In-Process (Spring ApplicationEventPublisher)** | 단순, 인프라 0, 50 TPS 부담 0 | 단일 애플리케이션 내에서만, fan-out 어려움 |
| B. Redis Stream | Redis 재사용, 가벼움 | 컨슈머 그룹 관리 필요 |
| C. RabbitMQ | 메시지 브로커 표준, 유연한 라우팅 | 운영 부담, 50 TPS에 과함 |
| D. Kafka | 대규모 메시지 처리, 긴 보존, fan-out | 운영 복잡도 ↑↑, 50 TPS에 명백히 과함 |

### 축 4. 미래 확장성 확보 방식

| 옵션 | Pros | Cons |
|---|---|---|
| A. 미래 확장 미고려 (단순) | 현재 코드 가장 단순 | 확장 시 광범위 수정 필요 |
| **B. EventPublisher 인터페이스 추상화** | 구현체만 교체로 도구 전환 가능, YAGNI 준수 | 인터페이스 한 단계 추가 |
| C. 미래 도구(Kafka) 미리 도입 | 한 번에 끝 | 현재 불필요한 운영 부담, YAGNI 위반 |

## Decision

**축 1: 옵션 B — Transactional Outbox 패턴 도입**
**축 2: 옵션 A — @Scheduled 폴링 (5초 주기)**
**축 3: 옵션 A — In-Process (Spring ApplicationEventPublisher)**
**축 4: 옵션 B — EventPublisher 인터페이스 추상화**

### 흐름

```
1. 결제 완료 시점:
   @Transactional
   public void completeBooking(...) {
       // 1) 비즈니스 로직 (PG 응답 받은 후)
       bookingRepository.save(booking);
       paymentRepository.save(payment);
       
       // 2) Outbox 이벤트 INSERT (같은 트랜잭션)
       outboxRepository.save(new OutboxEvent(
           eventType: "BookingCompleted",
           payload: bookingPayload,
           idempotencyKey: idempotencyKey,  // ADR-006 멱등키 통합
           status: "PENDING"
       ));
   }
   // COMMIT — booking + outbox atomicity 보장

2. 별도 스케줄러 (5초 주기):
   @Scheduled(fixedDelay = 5000)
   public void publishOutboxEvents() {
       List<OutboxEvent> events = outboxRepository.findByStatus("PENDING");
       for (OutboxEvent event : events) {
           try {
               eventPublisher.publish(event);  // 인터페이스 호출
               event.markAsPublished();
           } catch (Exception e) {
               log.error("Publish failed, will retry next cycle", e);
           }
       }
   }

3. 재발송 배치 (10분 이상 미발행분 별도 처리):
   @Scheduled(cron = "0 */5 * * * *")
   public void retryStaleEvents() {
       List<OutboxEvent> stale = outboxRepository
           .findByStatusAndCreatedBefore("PENDING", now().minusMinutes(10));
       // 재발송 시도
   }
```

### EventPublisher 인터페이스

```java
public interface EventPublisher {
    void publish(OutboxEvent event);
}

// 현재 구현 (in-process)
@Component
@Profile("default")
public class InProcessEventPublisher implements EventPublisher {
    private final ApplicationEventPublisher springPublisher;
    
    public void publish(OutboxEvent event) {
        springPublisher.publishEvent(event.toDomainEvent());
    }
}

// 향후 확장 (예시 — 도입 시)
// @Component
// @Profile("kafka")
// public class KafkaEventPublisher implements EventPublisher {
//     private final KafkaTemplate<String, OutboxEvent> kafkaTemplate;
//     public void publish(OutboxEvent event) {
//         kafkaTemplate.send("booking-events", event.getIdempotencyKey(), event);
//     }
// }
```

### In-Process 핸들러

```java
@Component
public class NotificationHandler {
    @Async
    @EventListener
    public void handle(BookingCompletedEvent event) {
        notificationService.send(event);
    }
}

@Component
public class StatisticsHandler {
    @Async
    @EventListener
    public void handle(BookingCompletedEvent event) {
        statisticsService.record(event);
    }
}
```

### Outbox INSERT 실패 시 fallback

산업 사례에서 운영 인사이트로 확인된 패턴: Outbox INSERT 실패가 비즈니스 로직 전체를 실패시키지 않도록 fallback 처리.

```java
try {
    outboxRepository.save(outboxEvent);
} catch (Exception e) {
    // Outbox 저장 실패: 비즈니스 로직 계속 (가용성 우선)
    // 로컬 로그에 fallback 기록 → 운영 복구 절차로 처리
    fallbackLogger.error("Outbox INSERT failed", outboxEvent, e);
}
```

이 정책의 근거: 결제는 이미 PG에서 청구된 상태이므로 Outbox 실패로 booking 자체를 실패시키면 사용자에게 *"돈은 빠졌는데 예약 없음"* 상황 발생. 가용성을 우선하고 누락 이벤트는 운영 복구로 처리.

### 멱등키 통합

ADR-006에서 결정된 멱등성 키를 OutboxEvent의 헤더에 포함. 재발송 시 컨슈머가 멱등 처리 가능.

```
OutboxEvent 구조:
  - id (auto-generated)
  - event_type (e.g., "BookingCompleted")
  - idempotency_key (ADR-006의 키 그대로)
  - payload (JSON)
  - status (PENDING / PUBLISHED)
  - created_at
  - published_at (nullable)
```

### 핵심 trade-off

- **Buy:**
  - PG 호출과 DB 커밋의 atomicity 보장 (망취소 시나리오 방어)
  - 서버 다운 시에도 이벤트 영구 유실 0%
  - 50 TPS 환경에서 부담 0의 가벼운 도구 (in-process)
  - 인터페이스 추상화로 미래 확장 시 코드 최소 수정
  - YAGNI 준수 — 현재 필요한 것만, 미래는 확장 포인트만

- **Pay:**
  - 폴링 주기(5초)만큼 이벤트 발행 지연
  - DB Outbox 테이블 추가 (스토리지 + 주기적 cleanup 필요)
  - in-process 핸들러는 단일 애플리케이션 내에서만 동작 (MSA 분리 시 도구 교체 필요)
  - Outbox INSERT 실패 시 fallback 절차 운영 부담

옵션 1A(직접 발행)를 선택하지 않은 이유: 망취소 시나리오에서 이벤트 유실 위험. 결제 시스템에서 알림/정산 누락은 사용자 신뢰 손상으로 직결.

옵션 1C(분산 트랜잭션)를 선택하지 않은 이유: 외부 시스템과의 XA는 사실상 사장된 패턴. PG 호출이 3~10초 걸리는 동안 DB lock 점유는 1000 TPS burst에서 lock contention.

옵션 2B(CDC)를 선택하지 않은 이유: Debezium은 강력하지만 Kafka Connect 인프라 필요. 운영 부담 대비 50 TPS 환경에서 가치 약함. 향후 트래픽 증가 시 재검토.

옵션 3B/3C/3D(메시지 브로커)를 선택하지 않은 이유:
- 컨슈머 수가 1~2개로 fan-out 가치 약함
- 단일 애플리케이션이라 MSA 통신 불필요
- 50 TPS는 in-process로 충분 (Redis Stream/RabbitMQ/Kafka 모두 과함)
- 운영 복잡도가 가치 대비 과함
- 메시지 브로커 도입 기준은 트래픽이 아니라 fan-out 필요성/MSA 분리/긴 메시지 보존인데, 본 시스템은 어느 것도 해당 없음

옵션 4C(Kafka 미리 도입)를 선택하지 않은 이유: YAGNI 위반. 현재 불필요한 운영 부담. 인터페이스 추상화로 향후 도입 시 구현체만 교체 가능.

## Consequences

**긍정 결과**
- PG 호출과 DB 커밋 정합성 보장 — 망취소 시나리오 방어.
- DB-이벤트 atomicity로 후처리 누락 0% (Outbox 진입한 이벤트 기준).
- 50 TPS 환경에서 운영 부담 최소 (DB 테이블 1개 + 스케줄러 2개만 추가).
- 인터페이스 추상화로 향후 Kafka/RabbitMQ 도입 시 구현체만 교체 가능.
- 산업 표준 패턴이라 신규 개발자 온보딩 비용 낮음.
- 멱등키 통합으로 컨슈머 측 멱등 처리 가능.

**부정 결과**
- 폴링 주기(5초)만큼 이벤트 발행 지연. 알림 즉시성이 매우 중요한 경우 부적합 (현재 시스템은 해당 없음).
- DB Outbox 테이블 누적 시 성능 저하 가능. 발행 완료 이벤트는 주기적 archive/delete 필요.
- in-process 핸들러는 동일 JVM 내에서만 동작. 향후 정산 등 별도 서비스 분리 시 메시지 브로커로 전환 필요.
- Outbox INSERT 자체가 실패하는 극단 케이스(DB 장애)에서는 별도 fallback 로깅 + 운영 복구 절차 필요.
- @Async 사용 시 스레드 풀 튜닝 필요 (현재 50 TPS 환경에서 기본값 충분).

**모니터링 필수 메트릭**
- Outbox 테이블의 PENDING 이벤트 수 (지연 감지)
- 10분 이상 미발행 이벤트 수 (재발송 배치 트리거)
- Outbox INSERT 실패율 (fallback 로깅 빈도)
- 핸들러 처리 시간 p50/p95/p99
- @Async 스레드 풀 사용률

### Consumer Idempotency 보장 책임

Outbox 폴러는 at-least-once delivery를 보장한다. 모든 Consumer는 이를 전제로 idempotent하게 설계해야 한다.

**규칙 1 — DB 쓰기만 있는 Consumer** (MySQL 8.0+):
```sql
INSERT INTO point_ledger (event_id, ...)
VALUES (?, ...)
ON DUPLICATE KEY UPDATE event_id = event_id;
-- event_id = event_id 는 의도적 no-op (UNIQUE 충돌 시 변경 없이 통과)
-- INSERT IGNORE는 데이터 truncation·FK 위반 등 다른 에러도 함께 무시하므로 비권장
```

**규칙 2 — 외부 호출(이메일, 알림 등)이 있는 Consumer** (write-first + status, MySQL 8.0+):
```sql
-- 1. INSERT ... ON DUPLICATE KEY UPDATE event_id = event_id (no-op)
INSERT INTO processed_event (event_id, status, ...) VALUES (?, 'INIT', ...)
ON DUPLICATE KEY UPDATE event_id = event_id;
-- 2. ROW_COUNT() == 0이면: 기존 row 존재 → status 확인
--    DONE → skip / INIT → 외부 호출 재시도
-- 3. ROW_COUNT() == 1이면: 신규 INSERT → 외부 호출 진행
-- 4. 외부 호출 성공 → UPDATE processed_event SET status = 'DONE'
-- 5. 외부 호출 실패 → status = 'INIT' 유지 (다음 재처리 시 재시도)
```

(MySQL `INSERT ... ON DUPLICATE KEY UPDATE`의 `ROW_COUNT()` 의미: 신규 INSERT 시 1, 기존 row 변경 없음 시 0. `event_id = event_id` no-op이므로 충돌 시 항상 0.)

`ON DUPLICATE KEY UPDATE event_id = event_id` 단독으로는 "write-only 함정" 발생: DB row는 남아 있지만 외부 호출이 영원히 실행되지 않음.

**규칙 3 — 이벤트 순서에 의존하지 않는다**: Outbox + @Async에서 순서 역전은 정상 동작.

**Consumer 분류**:
| 분류 | 방식 | 예시 |
|---|---|---|
| critical | @Async 제거, Outbox 폴러 트랜잭션 내 동기 실행 | 포인트 지급 |
| non-critical | @Async 유지 | 알림 발송, 통계 |

**processed_event retention**: 최소 24시간, 권장 7일 후 삭제.

**scope 명시**: `event_id`는 consumer 단위로 독립 처리된다. 서로 다른 consumer 간 dedup은 이 설계의 고려 범위가 아니다.

**재검토 시점**
- 별도 서비스 분리(MSA) 진행 시 (메시지 브로커 도입 검토 — RabbitMQ 또는 Kafka)
- 컨슈머 수가 3개 이상으로 증가할 때 (fan-out 효율 측면에서 메시지 브로커 검토)
- 평시 TPS가 1000 이상으로 증가할 때 (Outbox 폴링 부하 + 메시지 브로커 도입 검토)
- 알림 발행 지연이 5초로 부족하다는 비즈니스 요구 발생 시 (CDC 또는 즉시 발행 + 폴백 검토)
- 외부 시스템(정산, CRM 등)과의 연동이 추가될 때 (메시지 브로커가 표준 인터페이스로 가치 발생)
- Outbox 테이블 성능 저하가 관찰될 때 (archive 정책 강화 또는 CDC 전환)
