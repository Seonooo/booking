# docs/ — Documentation Guidelines

`docs/` 하위 작업 시 자동 누적 로드되는 가이드. root `CLAUDE.md` (Critical Constraints, Navigation Map, SLO) 다음에 본 파일이 추가 컨텍스트로 들어간다.

## 디렉토리 구조

```
docs/
├── ARCHITECTURE.md           # 헥사고날 패키지·인터페이스·요청 흐름 (ADR-014 정합)
├── ERD.md                    # 도메인 모델·DDL·시퀀스 다이어그램·상태 머신 단일 소스
├── TEST_MATRIX.md            # cross-feature dashboard (모든 Scenario Map 집계)
├── CONVENTIONS-FILE.md       # 파일 생성·위치·네이밍·구조 컨벤션 (단일 소스)
├── CONVENTIONS-CODE.md       # Java/SQL 코드 작성 컨벤션 + port/adapter 네이밍
├── CONVENTIONS-GIT.md        # Git branch/commit/scope/PR 컨벤션 (단일 소스)
├── adr/
│   ├── DECISIONS.md          # ADR 인덱스 + 핵심 결정 요약
│   └── ADR-NNN-*.md          # 개별 결정 (ADR-001 deprecated, 002~014 Accepted)
└── features/
    ├── _template.md          # feature 파일 템플릿
    ├── feature-NNN-*.md      # active features
    └── closed/               # 완료 feature archive
```

## 작업 시 우선 참조

- **결정 사항 / 배경**: `docs/adr/DECISIONS.md` 인덱스 → 해당 ADR 본문
- **도메인 모델 / DDL**: `docs/ERD.md`
- **파일 컨벤션**: `docs/CONVENTIONS-FILE.md` (변경 시 단일 소스로 우선 수정)
- **코드 컨벤션**: `docs/CONVENTIONS-CODE.md`
- **Git 컨벤션**: `docs/CONVENTIONS-GIT.md` (변경 시 본 문서 우선 → `scripts/execute.py` 동기화)
- **헥사고날 패키지·인터페이스**: `docs/ARCHITECTURE.md` (ADR-014 정합)

## ADR 작성 규칙

1. 새 ADR 신설 시 다음 번호 사용 (현재 014까지 사용)
2. 파일명: `ADR-NNN-kebab-case-title.md`
3. 형식: Status / Context / Options Considered / Decision / Consequences / Out of Scope / 관련 ADR (CONVENTIONS-FILE.md §1 참조)
4. 신설 후 `DECISIONS.md` 인덱스 표 + 핵심 결정 요약 §N 추가
5. 기존 ADR을 수정할 일이 생기면: amendment 섹션 추가 (본문 변경 금지) — ADR-009의 *"정정 사유"* 패턴 참고
6. ADR-001은 deprecated. 새 결정이 ADR-008 / ADR-014처럼 *기존 결정을 superseded* 하면 명시.

## ERD 사용 규칙

- 도메인 모델 변경 시 ERD를 단일 소스로 우선 수정 → 영향 ADR cross-ref 필수
- DDL은 `docs/ERD.md` §8 — Testcontainers init script로 활용
- 테이블 추가 시: 엔티티 사양 (§4) + ERD 다이어그램 (§5) + 상태 머신 (§6, 해당 시) + 시퀀스 (§7, 해당 시) + DDL (§8) 모두 갱신

## Feature 파일 컨벤션

- 위치: `docs/features/feature-NNN-name.md` (NNN은 기존 최대 번호 +1)
- 템플릿: `docs/features/_template.md` 복사 시작
- **Self-contained 원칙** (ADR-013): 외부 대화 컨텍스트 참조 금지. ADR/ERD/CRITICAL 제약을 Phase 본문에 inline.
- 작성 주체: `tdd-planner` agent (수동 작성 가능하지만 agent 권장)
- 실행 주체: `scripts/execute.py` 또는 main claude 수동
- 종료: Status `Done` + `docs/features/closed/` 로 이동
- TEST_MATRIX.md 동기화 의무 (active 추가/제거 시)

## TEST_MATRIX.md

cross-feature dashboard. `tdd-planner` agent가 feature 파일 갱신 시 동기화 (Step 6).
- §By Feature: 각 feature의 Scenario Map 요약
- §By Type: cross-feature `[happy]` / `[edge:CATEGORY]` 분포
- §By ADR: ADR별 edge case coverage audit (Edge Case 의무 조항 충족 여부)

## Testing Priorities

ADR-013 §ADR → 테스트 매핑 매트릭스가 단일 소스. 동시성 테스트 의무 영역: ADR-002 (Lua atomic) / ADR-006 (멱등성) / ADR-008 (재고 카운터).
