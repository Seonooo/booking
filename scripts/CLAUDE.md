# scripts/ — Automation Guidelines

`scripts/` 하위 작업 시 자동 누적 로드되는 가이드. root `CLAUDE.md` 다음에 본 파일이 추가 컨텍스트로 들어간다.

## 디렉토리 구조

```
scripts/
├── execute.py     # Feature-driven TDD automation (harness §E 채택)
├── adr_hook.py    # (기존) ADR 후크
└── README.md      # execute.py 사용법·환경 변수·안전 장치·트러블슈팅
```

## `execute.py` 변경 시 의무 절차

1. **`--dry-run` 회귀 검증 필수** — 변경 후 다음 커맨드로 prompt 조립 정상 작동 확인:
   ```bash
   python3 scripts/execute.py docs/features/feature-001-*.md --dry-run --phase 1
   ```
   `logs/execute-*-phase1-attempt1.log` 출력 확인 + exit 0.

2. **안전 장치 약화 금지**:
   - `PROTECTED_BRANCHES = {"main", "master"}` — 보호 브랜치 변경 금지
   - `MAX_RETRIES = 3` — 자가 교정 한도 변경은 ADR-013 amendment 동반 필요
   - `git add -A` 사용 금지 — 명시 path만 add (`src/`, `pom.xml`, `docs/features/`)
   - `--no-verify` 사용 금지 — pre-commit hook 실패 그대로 전파

3. **prompt 조립 순서 변경 시 ADR-013 §self-contained 원칙 검토**:
   - 현재 순서: CLAUDE.md → CONVENTIONS-FILE → CONVENTIONS-CODE → Applied ADRs → ERD → 이전 phase summary → 현재 Phase 본문 → reminder
   - 순서 변경 시 self-contained 원칙 깨지지 않는지 회귀 검증

4. **새 자동화 스크립트 추가 시 동일 안전 장치 적용**:
   - Protected branch 검사
   - `--dry-run` 모드 지원
   - logs/ 디렉토리에 자동 기록
   - 표준 라이브러리만 사용 (외부 Python 패키지 금지 — 의존 단순화)

5. **`docs/CONVENTIONS-GIT.md`와 정합 검증**:
   - branch prefix·commit format·scope 화이트리스트·2단계 커밋 정책 변경 시 **본 문서 우선 갱신** → `scripts/execute.py` 코드 동기화
   - 역방향 금지 (코드만 바꾸면 SSOT 부재)
   - 정합 표는 `docs/CONVENTIONS-GIT.md` §5 참조 (`PROTECTED_BRANCHES` ↔ §1, `two_stage_commit()` ↔ §3 등)

## 디버깅

- 모든 prompt + Claude 응답 + 검증 결과는 `logs/execute-<feature>-<timestamp>-phase<N>-attempt<N>.log` 에 누적 저장
- Phase 실패 시: 마지막 attempt 로그의 `--- VALIDATION FAILED ---` 섹션 확인
- `--verbose` 플래그로 디버그 로깅

## 외부 도구 의존

- `claude` CLI (Claude Code) — `claude --version` 확인
- `git` (필수)
- `mvn` 또는 `./mvnw` (검증 커맨드 실행)
- Python 3.10+ (표준 라이브러리만)

## 사용자가 자주 호출하는 패턴

```bash
# 새 작업 시 — 첫 phase 실행 + 사용자 확인
python3 scripts/execute.py docs/features/feature-NNN-*.md --interactive --phase 1

# 안전한 회귀 검증 (변경 직후)
python3 scripts/execute.py docs/features/feature-NNN-*.md --dry-run

# 전체 자동 진행 (테스트 / 검증 통과 신뢰 시)
python3 scripts/execute.py docs/features/feature-NNN-*.md --push
```

상세 가이드: `scripts/README.md`.
