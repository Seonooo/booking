# src/ — Architectural Patterns (stub)

> **본 파일은 stub입니다.** 첫 Java 소스 코드 작성 시점(`feature-001` Phase 3 GREEN 진입 시)에 본문을 채웁니다.

## 채울 시점

`feature-001` Phase 3.1 (Domain layer GREEN) 시작 시 본 stub을 본문으로 확장. 그 시점까지는 **root `CLAUDE.md`의 §Critical Constraints 6개 1-liner + ADR 직접 참조**로 작업.

## 채울 내용 (예정)

ADR-014 헥사고날 매핑 + Architectural Patterns 6개 코드 예시:

1. **Stock Control** (ADR-008) — Redis 재고 카운터 + 5분 TTL + PG 유예 60초
2. **Payment** (ADR-009) — Two-layer Strategy + Saga 보상
3. **Idempotency** (ADR-006) — Redis SETNX + DB unique constraint 이중 계층
4. **Events & Outbox** (ADR-010) — Transactional Outbox + EventPublisher 추상화
5. **Redis Resilience** (ADR-007) — Sentinel + Resilience4j Circuit Breaker + Fail-Closed
6. **Lua Atomic Operations** (ADR-002) — Redis Lua script atomic 처리

각 패턴마다:
- Driving / Driven port 매핑 (ADR-014)
- 핵심 인터페이스·구현체 코드 예시
- CRITICAL 제약 (CLAUDE.md §Critical Constraints)
- 관련 ADR cross-reference

## 채울 때 주의

- ARCHITECTURE.md §3 패키지 구조와 정합 유지
- ADR-009의 `CardPayment`/`YPayPayment`/`PointPayment`는 **ADR-014 기준 driven adapter**라 `infrastructure/payment/`로 위치 검토 (ARCHITECTURE.md §3 주석 참조)
- 코드 예시는 production-ready 수준 권장 — copy-paste 가능

## 현재 상태

- Spring Boot 3.5.14 LTS bootstrap 완료 (`BookingApplication` + 빈 패키지 골격 + `application.yml` 3종 + Flyway placeholder). **도메인 코드 0 lines 유지**.
- 첫 도메인 코드 작성 = `python3 scripts/execute.py docs/features/feature-001-*.md --interactive --phase 1`
