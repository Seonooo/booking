---
name: git-commit
description: Manual git commit guidance for the booking project. Activate when the user (or a sub-agent) needs to create commits, branches, or PRs OUTSIDE scripts/execute.py. References docs/CONVENTIONS-GIT.md as the single source of truth.
---

# Git Commit (booking project)

수동 git 작업 시 활성화. 정책 SSOT는 **`docs/CONVENTIONS-GIT.md`** — 본 skill은 절차·스코프 추론·실행만 담당.

## When to Activate

- 사용자가 `commit`, `PR`, `push`, `브랜치` 등을 언급하며 git 작업 의사 표시
- `git status` 에 staged / unstaged 변경이 있고 정리 commit이 필요한 시점
- ADR / feature / 컨벤션 문서 변경 후 단독 commit
- Sub-agent가 작업 결과를 commit해야 할 때 (예: `code-architect` 산출물 정리)

## When NOT to Activate

- **`scripts/execute.py` 자동 흐름 안** — execute.py가 `ensure_feature_branch()` + `two_stage_commit()`로 이미 처리. skill 개입 시 중복 commit 위험.
- 사용자가 명시적으로 *"수동으로 직접 commit할 거야"* 라고 한 경우

## Procedure

### 1. 현재 상태 파악

```bash
git status
git diff --stat
git diff --cached --stat
git log --oneline -5
git branch --show-current
```

### 2. SSOT 참조

**필수 참조**: `docs/CONVENTIONS-GIT.md`
- §1 → branch 명명
- §2 → type / scope 화이트리스트, subject 규칙
- §3 → 자동 vs 수동 2단계 커밋 정책 + 금지 사항
- §4 → PR template (PR 작성 시)

### 3. Scope 추론

변경 파일 경로 → scope 매핑 (CONVENTIONS-GIT.md §2):

| 변경 경로 | 추론 scope |
|---|---|
| `src/main/.../<feature-aggregate>/` 또는 `src/test/...` | feature-slug (예: `idempotency-handling`) |
| `docs/adr/`, `docs/adr/DECISIONS.md` | `adr` |
| `docs/ERD.md` | `erd` |
| `docs/ARCHITECTURE.md` | `arch` |
| `docs/CONVENTIONS-*.md` | `conventions` |
| `docs/features/` | `features` |
| `docs/TEST_MATRIX.md` | `test-matrix` |
| 그 외 `docs/*.md` | `docs` |
| `scripts/` | `scripts` |
| `CLAUDE.md`, `*/CLAUDE.md` | `claude` |
| `.claude/agents/` | `agents` |
| `.claude/skills/`, `.claude/commands/` | `skills` |
| `pom.xml`, `build.gradle*` | `build` |
| `.github/workflows/` | `ci` |

**혼재 시**: scope 분리 → commit을 둘로 나눈다. 임의 scope (`util`, `misc`, `etc`) 절대 금지.

### 4. Branch 확인

```bash
current=$(git branch --show-current)
```

- `current == main` → `<type>-<slug>` 새 브랜치 생성 제안 (사용자 confirm 후 실행)
- `current` 가 이미 feature 브랜치(`feat-*`/`fix-*`/`docs-*`/...) → 그대로 진행
- 브랜치 prefix와 commit type이 불일치 (예: `feat-foo` 위에서 `docs(...)` commit) → 사용자에게 확인 (의도적인지 / 별도 브랜치가 맞는지)

### 5. Commit Draft → Confirm → Execute

1. Type / scope / subject draft 제안 (CONVENTIONS-GIT.md §2 예시 형식)
2. 사용자에게 commit message 보여주고 confirm 받기
3. **명시 path만 stage** — `git add -A` / `git add .` 절대 금지
4. `git commit -m "<msg>"` (HEREDOC for multi-line)
5. **`--no-verify` 절대 금지**. pre-commit hook 실패 시 → 원인 수정 후 새 commit (amend 아님 — CONVENTIONS-GIT.md §3 금지 사항)

### 6. 2단계 분리 판단

CONVENTIONS-GIT.md §3 수동 예외에 해당하면 분리:
- *코드 + feature 파일 메타* → `feat(<slug>):` + `chore(<slug>): <meta description>`
- *문서 + 자동화 스크립트* → 문서 commit 먼저, 스크립트 commit 나중

해당 안 되면 단일 commit.

### 7. PR 생성 (요청 시)

CONVENTIONS-GIT.md §4 template 그대로:
```bash
gh pr create --title "<type>(<scope>): <subject>" --body "$(cat <<'EOF'
## What
...

## Why
...

## Test Plan
- [ ] ...

## Linked
- ADR: ...
- Feature: ...

## Risk / Rollback
...
EOF
)"
```

Merge 전략: **Squash merge** 기본. PR description이 squash commit body가 되도록 작성.

## Anti-Patterns (booking 프로젝트 한정)

- **execute.py 흐름 도중 수동 commit** — 자동 2단계 커밋과 충돌. 흐름 중단 후 수동 진입할 거면 execute.py를 명시적으로 종료
- **보호 브랜치 (`main`) 직접 commit** — 무조건 새 브랜치 생성
- **한 PR에 도메인 + 인프라 + 문서 혼재** — scope 별로 PR 분리
- **임의 scope 사용** (`feat(util):`, `fix(misc):`) — CONVENTIONS-GIT.md §2 화이트리스트만
- **`git add -A` / `--no-verify`** — CONVENTIONS-GIT.md §3 금지 사항. 시간 절약 목적이라도 사용 X
- **자동 commit branch (`feat-<slug>`) 위에서 다른 type commit** — 예: `feat-idempotency-handling` 위에서 `docs(adr)` commit. 별도 `docs-*` 브랜치 사용

## References

- **SSOT**: `docs/CONVENTIONS-GIT.md`
- **자동화**: `scripts/execute.py` (`ensure_feature_branch`, `two_stage_commit`) / `scripts/README.md`
- **루트 정책 요약**: `CLAUDE.md` §Git
