# 선착순 숙박 예약 시스템

자정에 오픈되는 한정 수량(10개) 숙박 상품의 실시간 선착순 예약 시스템.  
1000 TPS burst 환경에서 공정성(Fairness 100%)과 결제 무결성을 보장하도록 설계됨.

---

## 프로젝트 실행 방법

### 사전 요구 사항

- Java 17+
- Docker (Redis, PostgreSQL 로컬 실행용)

### 1. 인프라 실행

```bash
# Redis Sentinel + PostgreSQL 로컬 실행 (docker-compose.yml 준비 필요)
docker-compose up -d
```

### 2. 애플리케이션 빌드 및 실행

```bash
# Maven
./mvnw clean package
./mvnw spring-boot:run

# Gradle
./gradlew build
./gradlew bootRun
```

### 3. 테스트

```bash
# 단위 테스트
./mvnw test

# 통합 테스트 (Testcontainers — Docker 필요)
./mvnw verify

# 부하 테스트 (k6)
k6 run load-test/booking.js
```

---

## 전체 구조

```
booking/
├── docs/
│   ├── ARCHITECTURE.md       # 패키지 구조, 레이어, 인터페이스, DB 스키마
│   └── adr/
│       ├── DECISIONS.md      # ADR 인덱스 및 결정 흐름
│       └── ADR-00N-*.md      # 개별 결정 기록 (ADR-001 ~ ADR-010)
├── src/main/java/com/booking/
│   ├── api/                  # Controller, DTO, GlobalExceptionHandler
│   ├── application/          # Service (트랜잭션 경계, Saga 흐름)
│   ├── domain/               # 도메인 모델, Value Object, Repository 인터페이스
│   └── infrastructure/       # Redis Lua Script, PG Client, Outbox Poller, Resilience4j
├── .claude/
│   ├── agents/               # code-architect, java-reviewer 서브에이전트
│   └── commands/             # /review 커맨드
├── CLAUDE.md                 # Claude Code 가이드 (구현 규칙, 제약사항)
├── DECISIONS.md              # 주요 설계 쟁점 및 선택 근거 요약
└── README.md
```

### 핵심 기술 스택

| 영역 | 기술 |
|------|------|
| 프레임워크 | Spring Boot (Java) |
| 캐시 / 큐 | Redis (Sentinel HA) + Lua Script |
| DB | PostgreSQL / MySQL + HikariCP |
| 장애 대응 | Resilience4j (Circuit Breaker + Bulkhead) |
| 결제 | 외부 PG (mock) + Saga 보상 트랜잭션 |
| 이벤트 | Transactional Outbox + In-Process Publisher |

---

## API 목록

### 예약 API

| Method | Path | 설명 |
|--------|------|------|
| `GET` | `/checkout` | 주문서 조회 및 멱등성 키 발급 |
| `POST` | `/booking` | 예약 요청 (재고 확인 → 결제 → 예약 확정) |

#### POST /booking 요청 예시

```json
{
  "idempotency_key": "550e8400-e29b-41d4-a716-446655440000",
  "user_id": 1001,
  "product_id": 42,
  "amount": 150000,
  "payment_method": "CARD",
  "points": 0
}
```

#### 응답 코드

| 코드 | 의미 |
|------|------|
| `200` | 예약 성공 (또는 멱등성 캐시 응답) |
| `409` | 동일 멱등성 키로 요청 처리 중 |
| `422` | 동일 멱등성 키에 다른 payload 감지 |
| `429` | Rate Limit 초과 (userId 기준 Token Bucket) |
| `503` | Redis 장애 (Fail-Closed 정책) |

### 관리 API

| Method | Path | 설명 |
|--------|------|------|
| `POST` | `/admin/circuit-breaker/{name}/open` | Circuit Breaker 강제 OPEN |
| `POST` | `/admin/circuit-breaker/{name}/close` | Circuit Breaker 강제 CLOSE |
| `POST` | `/admin/saga/retry/{bookingId}` | Saga 보상 트랜잭션 수동 재시도 |

> 관리 API는 운영자의 수동 개입을 위한 진입점. 인증/인가 별도 구성 필요.
