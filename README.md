# 선착순 숙박 예약 시스템

자정에 오픈되는 **한정 수량 10개** 숙박 상품의 실시간 선착순 예약 시스템.
**500~1000 TPS burst** (자정 1~5분간) 환경에서 공정성과 결제 무결성을 보장하도록 설계됐다.

> **현재 상태: Pre-implementation.** 본 저장소는 ADR / 컨벤션 / 자동화 청사진을 우선 정립한 상태이며, Java 코드·인프라·테스트는 `scripts/execute.py` 기반 feature-driven TDD로 점진 구축한다. 모든 구현은 `docs/adr/` 결정을 따른다.

---

## 시스템 가정 / 경계

- **인증/인가는 외부 게이트웨이에서 완료**됨을 전제. API 요청의 `userId`는 인증된 식별자로 가정한다. 회원/인증/프로필 도메인은 시스템 경계 밖 (`docs/REQUIREMENTS.md` §5).
- **SLO 우선순위**: **Fairness 100% > Availability 99.9%**. Redis 장애 시 503은 재시도로 복구 가능하지만 무결성 훼손은 불가능 (ADR-007 Fail-Closed).
- **재고 모델**: burst 윈도우 내 정확히 10개 — 미달/초과판매 0건 (ADR-008).

---

## 사전 요구 사항

| 도구 | 용도 | 비고 |
|---|---|---|
| Java 17+ | 빌드·실행 | (`pom.xml` / `build.gradle`은 Phase 1 산출 — Planned) |
| Docker | MySQL 8.0+ / Redis Sentinel 로컬 실행 | `docker-compose.yml` Planned |
| Maven 또는 Gradle wrapper | 빌드 | `./mvnw` / `./gradlew` (Planned) |
| Python 3.10+ | `scripts/execute.py` 자동화 | 표준 라이브러리만 사용 |
| Claude CLI | feature-driven TDD 자동 진행 | `scripts/README.md` 참조 |
| k6 (선택) | 부하 테스트 | `load-test/` Planned |

---

## 디렉토리 개요

```
booking/
├── CLAUDE.md                  # Root navigation map (auto-loaded)
├── README.md                  # 본 파일
├── DECISIONS.md               # 주요 설계 쟁점 요약 (legacy notes)
├── .gitignore
├── docs/
│   ├── CLAUDE.md              # docs/ 작업 시 자동 로드
│   ├── REQUIREMENTS.md        # 요구사항 단일 출처
│   ├── ARCHITECTURE.md        # 헥사고날 패키지·인터페이스 (ADR-014)
│   ├── ERD.md                 # 도메인 모델·DDL·시퀀스·상태 머신 단일 출처
│   ├── TEST_MATRIX.md         # cross-feature 테스트 대시보드
│   ├── CONVENTIONS-FILE.md    # 파일 생성·구조 컨벤션 단일 출처
│   ├── CONVENTIONS-CODE.md    # Java/SQL 코드 컨벤션 단일 출처
│   ├── CONVENTIONS-GIT.md     # Git branch/commit/PR 컨벤션 단일 출처
│   ├── adr/
│   │   ├── DECISIONS.md       # ADR 인덱스 + 핵심 결정 요약
│   │   └── ADR-NNN-*.md       # 결정 기록 (ADR-001 deprecated, 002~014 Accepted)
│   └── features/
│       ├── _template.md
│       ├── feature-NNN-*.md   # active features
│       └── closed/            # 완료 archive
├── src/
│   ├── CLAUDE.md              # src/ 작업 시 자동 로드 (현재 stub)
│   ├── main/java/com/booking/ # Planned — api / application / domain / infrastructure (ADR-014)
│   └── test/java/com/booking/ # Planned — unit / integration / concurrency / testsupport
├── scripts/
│   ├── CLAUDE.md
│   ├── execute.py             # Feature-driven TDD 자동화
│   ├── adr_hook.py
│   └── README.md
├── load-test/                 # Planned — k6 스크립트
└── .claude/
    ├── agents/                # 5종 sub-agents
    ├── commands/              # /review, /adr-sync slash commands
    ├── skills/git-commit/     # 수동 commit 가이드
    └── settings.json
```

전체 디렉토리 컨벤션은 `docs/CONVENTIONS-FILE.md` §9 참조.

---

## 핵심 기술 스택

| 영역 | 기술 | ADR |
|---|---|---|
| 프레임워크 | Spring Boot 3.x, Java 17+ | ADR-014 (헥사고날) |
| DB | MySQL 8.0+ + HikariCP | ADR-008 (재고 카운터) |
| 캐시 / 원자 연산 | Redis Sentinel HA + Lua Script | ADR-002, ADR-006, ADR-008 |
| 멱등성 | Redis SETNX (1차) + DB UNIQUE (2차) + body SHA256 | ADR-006 |
| 결제 | 외부 PG (mock) + Strategy + Saga 보상 | ADR-009 |
| 장애 대응 | Resilience4j (Circuit Breaker + Bulkhead) + Fail-Closed | ADR-007 |
| 이벤트 | Transactional Outbox + In-Process Publisher | ADR-010 |
| 정합성 워커 | PG idempotency reconciliation (6분 trigger) | ADR-011 |
| 테스트 전략 | Mixed Test-First (RED 의무 영역 + 사후 허용) + Testcontainers | ADR-013 |

---

## 개발 워크플로우 — Feature-driven TDD

본 프로젝트는 `docs/features/feature-NNN-*.md` 파일을 단일 진실의 출처로 두고, **`scripts/execute.py`** 가 Phase 0~6을 헤드리스 Claude로 자동 진행한다 (`harness_framework` §E 채택).

### 기본 흐름

```bash
# 1. tdd-planner 서브에이전트가 feature 파일 작성
#    - ADR 식별 → Gherkin Scenario → Phase 0~6 + 검증 커맨드
#
# 2. execute.py가 자동 진행
python3 scripts/execute.py docs/features/feature-001-idempotency-handling.md

# 단일 phase
python3 scripts/execute.py docs/features/feature-001-*.md --phase 2

# Dry-run (prompt 조립만 확인)
python3 scripts/execute.py docs/features/feature-001-*.md --dry-run

# Push까지 자동 (feat-<slug> 브랜치 + 2단계 커밋)
python3 scripts/execute.py docs/features/feature-001-*.md --push
```

각 phase는 self-correction 3회 + 검증 게이트 통과 시 2단계 커밋 (`feat(<slug>):` + `chore(<slug>): phase-N done`).

상세 가이드: `scripts/README.md`.

### 수동 빌드·테스트 (구현 진척 시)

```bash
./mvnw clean package           # 또는 ./gradlew build
./mvnw test                    # 단위
./mvnw verify                  # Testcontainers 통합
k6 run load-test/<feature>.js  # 부하 (Planned)
```

### Sub-agents

| Agent | 역할 |
|---|---|
| `tdd-planner` | 비-trivial 작업 시작 시 feature 파일 작성 |
| `code-architect` | Phase 1 architectural blueprint |
| `test-author` | Phase 2 RED / Phase 6 Concurrency 테스트 작성 |
| `java-reviewer` | Java/Spring Boot 코드 리뷰 (CRITICAL 제약 검사) |
| `database-reviewer` | MySQL 8.0+ 스키마·쿼리 리뷰 |

세부 사용 시점은 `.claude/agents/<agent>.md`.

---

## API 목록

> 정확한 요청·응답 스키마는 ADR / `docs/ARCHITECTURE.md` / 구현된 controller 본문이 단일 출처. 본 표는 개요만.

### Public API

| Method | Path | 설명 | 관련 ADR |
|---|---|---|---|
| `GET` | `/checkout` | 주문서 조회 — 상품 정보 + 사용자 가용 포인트 + 멱등성 키 발급 | ADR-006 |
| `POST` | `/booking` | 예약 — 재고 확인 → 결제 → 예약 확정 (`PaymentComposition` 적용) | ADR-006, ADR-008, ADR-009 |

### POST /booking 응답 코드

| 코드 | 의미 | 근거 ADR |
|---|---|---|
| `200` | 성공 / 멱등성 캐시 응답 | ADR-006 |
| `400` | 도메인 invariant 위반 (`PaymentComposition` 등) | ADR-009 |
| `409` | 동일 멱등성 키 처리 중 | ADR-006 |
| `422` | 동일 멱등성 키에 다른 payload 감지 | ADR-006 |
| `429` | Rate Limit 초과 (userId 기준 Token Bucket) | ADR-005 |
| `503` | Redis Fail-Closed | ADR-007 |

전체 status code 정의는 `docs/CONVENTIONS-CODE.md` §3.

---

## 주요 진입점 (어디부터 읽을지)

| 목적 | 시작점 |
|---|---|
| 시스템 결정·트레이드오프 이해 | `docs/adr/DECISIONS.md` 인덱스 → 개별 ADR |
| 도메인 모델·DDL·상태 머신 | `docs/ERD.md` |
| 헥사고날 패키지·인터페이스 | `docs/ARCHITECTURE.md` |
| 요구사항 단일 출처 | `docs/REQUIREMENTS.md` |
| 작업 시작 (feature 정의) | `docs/features/_template.md` + `tdd-planner` agent |
| 자동화 사용법 | `scripts/README.md` |
| Git 컨벤션 | `docs/CONVENTIONS-GIT.md` |
| Claude Code 협업 가이드 | `CLAUDE.md` (root) → 디렉토리별 `CLAUDE.md` 자동 로드 |

---

## Out of Scope

본 시스템 구현 의무 밖 (`docs/REQUIREMENTS.md` §5 참조):

- 회원 / 인증 / 프로필 도메인 (외부 게이트웨이 가정)
- Cancel API / 환불 도메인 (ADR-012 Planned, forward path 미진입)
- 회계 / 정산 도메인
- 관측성 / 운영 절차 / Runbook
- Admin API (Circuit Breaker 수동 제어 / Saga 수동 재시도) — Resilience4j 자동 동작 + ADR-011 Reconciliation Worker로 대체
