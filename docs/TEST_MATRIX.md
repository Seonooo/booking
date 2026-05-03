# Test Matrix — booking system

> 모든 feature의 Scenario Map을 집계한 프로젝트 전역 dashboard.
> `tdd-planner` agent가 feature 파일 생성/갱신 시 동기화한다 (Step 6).
> 단일 소스: 각 `docs/features/feature-NNN-*.md`의 `### Scenario Map` 섹션. 충돌 시 feature 파일 우선.

## By Feature (active)

### feature-001: POST /booking 멱등성 처리 (ADR-006)

| Scenario | Type | Status |
|---|---|---|
| 신규 멱등성 키 → 200 OK + booking 생성 | happy | pending |
| 같은 키 + 같은 body, 처리 중 → 409 | happy | pending |
| 같은 키 + 같은 body, 이미 완료 → 200 + 캐시 | happy | pending |
| 같은 키 + 다른 body → 422 | edge:tampering | pending |
| 동시 동일 키 100건 → 1건만 성공, 99건 409 | edge:concurrency | pending |
| TTL 15분 만료 후 같은 키 재시도 → 200 | edge:expiry | pending |
| Redis 장애 + DB unique 차단 | edge:failure | pending |

---

## By Type — Cross-feature

### `[happy]`
- feature-001 §1: 신규 멱등성 키 → 200 OK (ADR-006)
- feature-001 §2: 같은 키 + 같은 body, 처리 중 → 409 (ADR-006)
- feature-001 §3: 같은 키 + 같은 body, 이미 완료 → 200 + 캐시 (ADR-006)

### `[edge:boundary]`
- (아직 없음)

### `[edge:failure]`
- feature-001 §7: Redis 장애 + DB unique 차단 (ADR-006/007)

### `[edge:concurrency]`
- feature-001 §5: 동시 동일 키 100건 → 1건만 성공 (ADR-006)

### `[edge:tampering]`
- feature-001 §4: 같은 키 + 다른 body → 422 (ADR-006)

### `[edge:expiry]`
- feature-001 §6: TTL 15분 만료 후 같은 키 재시도 → 200 (ADR-006)

---

## By ADR — Edge Case Coverage Audit

ADR이 결정한 핵심 시나리오가 테스트 영역에 1:1 매핑되는지 audit.

| ADR | happy | edge:boundary | edge:failure | edge:concurrency | edge:tampering | edge:expiry | 의무 충족 |
|---|---|---|---|---|---|---|---|
| ADR-002 (Lua atomic) | 0 | 0 | 0 | 0 | 0 | 0 | ❌ feature 미생성 |
| ADR-005 (Token Bucket) | 0 | 0 | 0 | 0 | 0 | 0 | ❌ feature 미생성 |
| ADR-006 (멱등성) | 3 | 0 | 1 | 1 | 1 | 1 | ✅ |
| ADR-007 (Circuit Breaker) | 0 | 0 | 0 | 0 | 0 | 0 | ❌ feature 미생성 |
| ADR-008 (재고 카운터) | 0 | 0 | 0 | 0 | 0 | 0 | ❌ feature 미생성 |
| ADR-009 (Saga) | 0 | 0 | 0 | 0 | 0 | 0 | ❌ feature 미생성 |
| ADR-010 (Outbox) | 0 | 0 | 0 | 0 | 0 | 0 | ❌ feature 미생성 |
| ADR-011 (Reconciliation) | 0 | 0 | 0 | 0 | 0 | 0 | ❌ feature 미생성 |

**의무 충족** = feature 파일 존재 + `[edge:*]` ≥1 (ADR-013 §Edge Case 의무 조항).

---

## Summary

- **Total active features**: 1 (feature-001)
- **Total scenarios**: 7
- **Edge case coverage**: 4/7 (57%) — `[edge:tampering]` 1, `[edge:concurrency]` 1, `[edge:expiry]` 1, `[edge:failure]` 1
- **ADR coverage**: 1/8 (ADR-006만 활성화)
- **Status 분포**: pending 7 / RED 0 / GREEN 0 / done 0

**Next priority** (ADR 매트릭스 기준 우선순위):
1. ADR-008 재고 카운터 — flash-sale 핵심. `[edge:concurrency]` 의무.
2. ADR-009 Saga — DB 실패 시 PG 취소 검증. `[edge:failure]` 의무.
3. ADR-010 Outbox — at-least-once + Consumer ROW_COUNT() 분기. `[edge:failure]` + `[edge:concurrency]` 의무.
4. ADR-011 Reconciliation — UNKNOWN → COMPLETED/FAILED 전이. `[edge:expiry]` + `[edge:failure]` 의무.

---

## Closed (archive)

(아직 없음 — feature가 `docs/features/closed/` 로 이동하면 여기에 요약 한 줄만 보존)
