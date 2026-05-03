# scripts/

Utility scripts for the booking system project.

## `execute.py` — Feature-driven TDD automation

`scripts/execute.py`는 `docs/features/feature-NNN-*.md` 파일을 읽어 Phase 0~6(+sub-phase)을 헤드리스 Claude로 자동 실행한다. harness framework(jha0313/harness_framework) §E를 우리 환경에 맞게 통합한 것.

### 동작

매 phase마다:

1. **Prompt 조립**: `CLAUDE.md` + 적용 ADR + `ERD.md` + 이전 phase summary + 현재 phase 본문 + self-contained reminder
2. **Claude 호출**: `claude -p "<prompt>"` (headless)
3. **검증 커맨드 실행**: feature 파일에 명시된 `mvn test ...`
4. **자가 교정**: 실패 시 stderr를 다음 retry prompt에 prepend (최대 3회)
5. **Progress Log + Status 갱신**: 자동 timestamp + summary
6. **2단계 커밋**: `feat(<feature>): <summary>` (코드) + `chore(<feature>): phase-N done` (메타)

### 사용법

```bash
# 전체 feature 자동 실행 (Phase 1~6 순차)
python3 scripts/execute.py docs/features/feature-001-idempotency-handling.md

# 단일 phase만
python3 scripts/execute.py docs/features/feature-001-*.md --phase 2

# Sub-phase 단위
python3 scripts/execute.py docs/features/feature-001-*.md --phase 3.2

# Dry run — prompt만 출력, Claude/git/검증 모두 skip
python3 scripts/execute.py docs/features/feature-001-*.md --dry-run

# Interactive — 각 phase 시작 전 사용자 confirmation
python3 scripts/execute.py docs/features/feature-001-*.md --interactive

# Push까지 자동
python3 scripts/execute.py docs/features/feature-001-*.md --push
```

### 환경 변수

- `ANTHROPIC_API_KEY` — Claude Code CLI가 사용. `~/.bashrc` / `~/.zshrc` 또는 `.env` 파일에 설정 (절대 git 커밋 X — `.gitignore` 확인).

### 사전 요구

- Python 3.10+
- `claude` CLI 설치 (Claude Code) — `claude --version` 으로 확인
- `git` (필수)
- `mvn` 또는 `./mvnw` (검증 커맨드 실행용)
- 표준 Unix tools (subprocess가 사용)

외부 Python 패키지 없음 — 표준 라이브러리만 사용.

### 안전 장치

| 메커니즘 | 동작 |
|---|---|
| **`--dry-run`** | prompt만 출력, Claude 호출/git 커밋/검증 모두 skip |
| **`--phase N`** | 단일 phase만. 자동 다음 phase 진입 안 함 |
| **`--interactive`** | 한 phase 시작 전 사용자 `y/N` confirmation |
| **검증 게이트 엄격** | 검증 커맨드 통과 안 하면 절대 커밋 X |
| **Protected branch 차단** | `main`/`master` 위에서 commit 시도하면 abort |
| **`git add -A` 금지** | 명시 path만 add (`src/`, `pom.xml`, `docs/features/`) |
| **`--no-verify` 사용 안 함** | pre-commit hook 실패 그대로 전파 |
| **로그 자동 저장** | `logs/execute-<feature>-<timestamp>.log` 에 prompt + 응답 + 검증 결과 누적 |
| **Retry 한도 3회** | 4회째부터는 `blocked` 상태 + 사용자 개입 요청 |
| **Phase timeout** | 30분 / phase. 초과 시 abort. |

### Self-correction 흐름

```
Phase X 시도
  ↓
prompt 조립 → claude -p
  ↓
검증 커맨드 실행 (mvn test ...)
  ↓
  ├─ 통과 → Progress Log + 2단계 커밋 → 다음 phase
  └─ 실패 → retry (최대 3회):
       └─ 이전 prompt + "Previous attempt failed: <stderr>" prepend → 재실행
       └─ 3회 초과 → status: BLOCKED → script exit 1 → 사용자 개입
```

### RED phase 특수 동작

`Phase 2 RED`는 검증 커맨드가 **모두 실패해야 통과**로 간주. 모두 통과하면 RED phase의 의도(production 코드 미구현 상태에서 실패 검증)에 어긋나므로 retry 트리거.

### Feature 파일 매핑

execute.py는 별도 step 파일을 만들지 않고 feature 파일의 다음 섹션을 활용:

- `## Execution Plan (TDD)` 섹션
  - `### Phase N: ...` / `#### Phase N.M: ...` heading
  - 각 phase 본문의 `**검증 커맨드**:` ```bash``` 블록
  - 각 phase 본문의 `**AC**:` 라인
- `## Progress Log` 섹션 (append-only 자동 갱신)
- frontmatter Status 컬럼 (Draft → In-Progress → Done)
- Phase 0 Context의 `Applied ADRs` + `영향 엔티티` (prompt 조립 시 활용)

### 트러블슈팅

| 증상 | 원인 / 해결 |
|---|---|
| `claude CLI not found` | Claude Code 설치 확인. `which claude`. |
| `Phase X failed after 3 retries` | `logs/execute-*-attempt3.log` 에서 마지막 stderr 확인. 수동으로 코드 수정 후 `--phase X` 재실행. |
| `Refusing to commit on protected branch 'main'` | `git checkout -b feat-<slug>` 로 feature 브랜치 만든 뒤 재실행. (자동 생성도 시도하지만 시작점이 main인 경우만) |
| Validation timeout (30분) | `PHASE_TIMEOUT_SEC` 상수를 늘리거나, phase를 sub-phase로 분할. |
| `mvn test` 자체가 빌드 실패 | Phase 1 (Architectural Blueprint) 미완료. Phase 1부터 다시. |

### Out of Scope (별도 plan)

- CI/CD 통합 (GitHub Actions에서 execute.py 실행) — 본 스크립트는 로컬 사용 가정
- Property-based 테스트 자동 생성
- Allure 리포트 통합
