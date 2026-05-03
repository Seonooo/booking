# Conventions — Git

Git **branch / commit / PR** 작성 시 적용. 파일 생성·구조 컨벤션은 `CONVENTIONS-FILE.md`, 코드 컨벤션은 `CONVENTIONS-CODE.md` 참조.

본 파일은 **단일 진실의 원천(SSOT)**. `scripts/execute.py` 자동화·`.claude/skills/git-commit/SKILL.md`·agent 모두 본 파일을 ref로 가리킨다. 정책 변경은 본 파일을 우선 수정한 뒤 자동화 코드 동기화.

---

## §1. Branch 명명

| Prefix | 용도 | 예시 | 생성 주체 |
|---|---|---|---|
| `feat-<slug>` | 기능 구현 (feature 파일 동반) | `feat-idempotency-handling` | `scripts/execute.py` 자동 |
| `fix-<slug>` | 버그 수정 (production 영향 없음) | `fix-stock-decr-race` | 수동 |
| `docs-<slug>` | 문서·ADR·feature 파일·CLAUDE.md만 변경 | `docs-conventions-git` | 수동 |
| `hotfix-<slug>` | production 긴급 수정 | `hotfix-pg-timeout` | 수동 |
| `chore-<slug>` | 의존성·빌드·인프라·도구 | `chore-bump-spring-boot` | 수동 |
| `refactor-<slug>` | 행위 변경 없는 구조 개선 | `refactor-extract-idempotency-port` | 수동 |

**규칙**:
- `<slug>`는 `kebab-case` (`CONVENTIONS-FILE.md` §8 동일)
- 보호 브랜치: **`main`** (= `scripts/execute.py` `PROTECTED_BRANCHES`)
- 보호 브랜치에 직접 commit / force push 금지
- 한 브랜치 = 한 의도 (feature·fix·docs 혼재 금지 → §4 PR 정책 참조)

---

## §2. Commit format

### 형식

```
<type>(<scope>): <subject>

[optional body]

[optional footer]
```

### Type 화이트리스트

| Type | 용도 |
|---|---|
| `feat` | 새 기능 / 새 동작 |
| `fix` | 버그 수정 |
| `docs` | 문서·주석만 변경 |
| `refactor` | 행위 변경 없는 구조 개선 |
| `test` | 테스트만 추가/수정 |
| `chore` | 의존성·빌드·도구·메타 작업 |
| `perf` | 성능 개선 |
| `revert` | 이전 commit 되돌림 |

### Scope 화이트리스트

| 분류 | Scope | 매칭되는 파일 경로 |
|---|---|---|
| **도메인 / 구현** | `<feature-slug>` (예: `idempotency-handling`) | `src/main/...`, `src/test/...` 변경 — `execute.py` 자동 commit 경로 |
| **문서** | `docs` | `docs/*.md` 일반 문서 |
| **문서 (특정)** | `adr`, `erd`, `arch`, `conventions`, `features`, `test-matrix` | `docs/adr/`, `docs/ERD.md`, `docs/ARCHITECTURE.md`, `docs/CONVENTIONS-*.md`, `docs/features/`, `docs/TEST_MATRIX.md` |
| **인프라 / 도구** | `scripts`, `claude`, `agents`, `skills`, `ci`, `build` | `scripts/`, `CLAUDE.md` 계열, `.claude/agents/`, `.claude/skills/`, `.github/workflows/`, `pom.xml`/`build.gradle` |

**규칙**:
- 한 commit은 단일 scope 원칙 (혼재 시 commit 분리)
- 도메인 변경은 `<feature-slug>` 사용 (`feat(idempotency-handling): ...`) — execute.py와 정합
- 메타 변경은 위 화이트리스트만 사용. **임의 scope 금지** (예: `feat(util):`, `feat(misc):`)

### Subject

- 명령형 (`add`, `fix`, `update` — `added`/`fixed` 금지)
- 마침표 없음
- 50자 이내 권장
- 영어 / 한글 모두 허용 (`CONVENTIONS-FILE.md` §2 mix 원칙 동일)

### 예시

```
feat(idempotency-handling): persist idempotency key with body hash
fix(stock-counter): prevent decrement below zero on Lua eval
docs(adr): add ADR-015 for circuit breaker policy
docs(conventions): clarify scope whitelist for meta commits
chore(scripts): add CONVENTIONS-GIT pointer to execute.py docstring
test(idempotency-handling): cover concurrent same-key requests
refactor(arch): extract Driven port for PG client
```

---

## §3. 2단계 커밋 정책

### 자동 (execute.py)

매 phase 완료 시:
1. `feat(<feature-slug>): <summary>` — 코드 변경 (`src/`, `pom.xml`)
2. `chore(<feature-slug>): phase-N done` — 메타 변경 (`docs/features/feature-NNN-*.md` Progress Log + Status)

빈 stage는 skip (코드 변경 0건이면 1번 commit 생략).

### 수동

- **단일 commit 원칙**. 한 의도 = 한 commit.
- **예외 — 2단계 분리 의무**:
  - *코드 변경 + feature 파일 메타* 동시 수정 → execute.py와 같은 패턴으로 분리 (`feat(<slug>):` + `chore(<slug>):`)
  - *문서 + 자동화 스크립트* 동시 수정 → 문서 먼저, 스크립트 나중 (SSOT → 코드 순서)

### 금지 사항 (자동·수동 공통)

| 금지 | 이유 |
|---|---|
| `git add -A` / `git add .` | `.env` / 빌드 산출물 / 로그 우발 commit 위험 — 명시 path만 add |
| `git commit --no-verify` | pre-commit hook 우회 → 검증 게이트 무력화 |
| `git commit --amend` (push 이후) | 공유 history 변조 |
| `git push --force` (보호 브랜치) | 동료 작업 손실 — `--force-with-lease`도 보호 브랜치엔 금지 |

명시 path 예시:
```bash
git add src/ pom.xml                         # 도메인 commit
git add docs/features/feature-001-*.md       # feature 메타
git add docs/adr/ADR-015-*.md docs/adr/DECISIONS.md   # ADR 추가
git add CLAUDE.md docs/CONVENTIONS-GIT.md    # 컨벤션 변경
```

---

## §4. PR 정책

### Title

`<type>(<scope>): <subject>` — 첫 commit subject 그대로. 50자 초과 시 첫 commit subject를 줄이는 게 우선.

### Description 템플릿

```markdown
## What
(이 PR이 무엇을 변경하는지 1-3 문장)

## Why
(배경 / 동기 — 관련 issue / ADR / feature 파일 링크)

## Test Plan
- [ ] (검증한 시나리오 / 실행한 커맨드)

## Linked
- ADR: ADR-NNN
- Feature: docs/features/feature-NNN-*.md
- Issue: #NNN (있으면)

## Risk / Rollback
(영향 범위 / 롤백 절차 — hotfix·refactor는 필수)
```

### Merge 전략

- **Squash merge** (`feature 브랜치 → main`): 자동 commit이 다수일 때 압축. PR description이 squash commit body가 되도록 작성.
- 단, **commit history 보존 가치가 있는 경우** (예: 여러 feature를 한 PR에 묶지 못하고 sub-feature 단위로 분리한 refactor) → `Rebase and merge` 검토.
- `Merge commit` 금지 (history 노이즈).

### 보호 브랜치 규칙

- `main` direct push 금지 (PR만 허용)
- `main` force push 금지
- PR merge 전 검증 게이트 통과 의무 (CI 추가 시 강제)

---

## §5. 자동화 정합

### `scripts/execute.py`와의 관계

`scripts/execute.py`는 본 문서 §1·§2·§3을 코드로 구현한다:

| 본 문서 | execute.py 구현 |
|---|---|
| §1 보호 브랜치 `main` | `PROTECTED_BRANCHES = {"main", "master"}` |
| §1 `feat-<slug>` 자동 생성 | `ensure_feature_branch()` |
| §3 자동 2단계 커밋 | `two_stage_commit()` |
| §3 `git add -A` 금지 | 명시 path만 (`src/`, `pom.xml`, `docs/features/`) |
| §3 `--no-verify` 금지 | 미사용 (pre-commit hook 그대로 전파) |

### 변경 시 동기화 의무

본 문서 §1~§3 변경 → `scripts/execute.py` 동기화 → `scripts/CLAUDE.md` 의무 절차 갱신.
**역방향 금지**: 코드만 바꾸면 SSOT 부재. 항상 본 문서 우선.

### `.claude/skills/git-commit/`와의 관계

수동 commit 시점에 활성화되는 skill. 본 문서를 SSOT로 참조해 scope 추론·branch 명명·2단계 분리 판단.

---

## §6. 의도적 비포함 (Out of Scope)

- **CI/CD 워크플로우 (GitHub Actions)** — 본 프로젝트는 로컬 자동화(`execute.py`) 우선. CI 도입 시 별도 ADR.
- **Release / Tag / Changelog 정책** — pre-implementation 단계. 첫 release 시 본 문서에 §7 추가.
- **Conventional Commits 외 commit 메시지 규약** — 본 프로젝트는 Conventional Commits 사용.
- **Git hook 구현** (pre-commit lint 등) — 별도 plan.
- **Co-Authored-By / Signed-off-by** — 향후 협업 시 결정.
