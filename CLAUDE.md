# CLAUDE.md

Navigation map for Claude Code working on this repository. Detailed context lives in area-specific `CLAUDE.md` files (auto-loaded when working in that directory) + dedicated convention files.

## Project Status

Pre-implementation phase. **Spring Boot (Java)** booking system. ADRs are authoritative — implementation must follow them.

## SLO Priority

**Fairness 100% > Availability 99.9%** — drives all Fail-Closed decisions. Redis 장애 시 503은 재시도로 복구 가능하지만 무결성 훼손은 불가능.

## Build & Run

- Build: `./mvnw clean package` 또는 `./gradlew build`
- Test: `./mvnw test` (unit) / `./mvnw verify` (Testcontainers integration)
- Load test: `k6 run load-test/<feature>.js`
- **자동 실행**: `python3 scripts/execute.py docs/features/feature-NNN-*.md [--dry-run|--phase N|--interactive|--push]` — feature 파일을 읽어 Phase 0~6 자동 진행. 자세한 내용 `scripts/README.md`.

## Critical Implementation Constraints (1-liner)

코드 예시는 `src/CLAUDE.md` (첫 코드 작성 시 채움). 본 1-liner는 항상 적용:

1. **PG 호출은 DB 트랜잭션 밖** (ADR-009)
2. **Redis 원자 연산은 Lua Script** — 단일 키든 다중 키든 "검사 + 변경" 분리 시 race (ADR-002)
3. **Outbox 폴러는 분산 락 필수** — ShedLock 또는 SELECT FOR UPDATE SKIP LOCKED (ADR-010)
4. **멱등성 키 = 클라이언트 UUID + body SHA256 해시 + Redis 1차/DB UNIQUE 2차** (ADR-006)
5. **Redis 의존 컴포넌트 Fail-Closed → 503** — Fail-Open 금지 (ADR-007)
6. **Outbox INSERT 실패 시 fallback 로깅, 트랜잭션 롤백 X** — PG 청구 후 booking 보존 (ADR-010)

## Navigation Map

| 영역 | 파일 | 자동 로드 |
|---|---|---|
| ADR / 결정 | `docs/adr/DECISIONS.md` (인덱스) | docs/ 작업 시 |
| 도메인 모델 / DDL | `docs/ERD.md` | docs/ 작업 시 |
| 헥사고날 패키지 / 인터페이스 | `docs/ARCHITECTURE.md` (ADR-014 정합) | docs/ 작업 시 |
| **파일 생성·구조 컨벤션** | `docs/CONVENTIONS-FILE.md` | docs/ 작업 시 |
| **코드 작성 컨벤션** | `docs/CONVENTIONS-CODE.md` | docs/ 작업 시 |
| **Git 컨벤션 (branch / commit / PR)** | `docs/CONVENTIONS-GIT.md` | docs/ 작업 시 |
| Feature 파일 컨벤션 / TEST_MATRIX | `docs/CLAUDE.md` | docs/ 작업 시 |
| Architectural Pattern 코드 예시 | `src/CLAUDE.md` | src/ 작업 시 (현재 stub) |
| 자동 실행 / execute.py | `scripts/CLAUDE.md` + `scripts/README.md` | scripts/ 작업 시 |
| Agent 사용 시점 | `.claude/agents/<agent>.md` | 명시 호출 시 |

## ADR Index (요약)

전체 인덱스 `docs/adr/DECISIONS.md`. 현재 상태: ADR-002~014 Accepted (ADR-001 deprecated by ADR-008).

## Feature 파일 컨벤션 (요약)

비-trivial 작업은 `docs/features/feature-NNN-name.md` 단일 진실의 원천. 자세한 내용 `docs/CLAUDE.md`.

- Self-contained 원칙 (ADR-013) — 외부 대화 컨텍스트 참조 금지
- `tdd-planner` agent가 작성, `scripts/execute.py`가 실행
- 종료 시 Status `Done` + `docs/features/closed/` 이동

## Git / Agent (요약)

- **Git 정책 SSOT**: `docs/CONVENTIONS-GIT.md` — branch / commit / scope 화이트리스트 / 2단계 커밋 / PR 템플릿
- **자동 commit**: `scripts/execute.py` (`feat-{feature-slug}` 자동 브랜치 + 2단계 커밋)
- **수동 commit**: `.claude/skills/git-commit/SKILL.md` skill 활성화 — main claude / sub-agent 양쪽
- **Agent (5종)**: `tdd-planner` / `test-author` / `code-architect` / `java-reviewer` / `database-reviewer` — 사용 시점은 `.claude/agents/<agent>.md`
