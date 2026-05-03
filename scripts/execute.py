#!/usr/bin/env python3
"""
execute.py — Feature-driven TDD automation for the booking system.

Reads a feature file (docs/features/feature-NNN-*.md), parses Phase 0~6
(+sub-phases 3.1, 3.2, ...), and runs each phase via headless Claude Code:
- Assembles guardrail prompt (CLAUDE.md + ADRs + ERD + phase body)
- Calls `claude -p "<prompt>"` (headless mode)
- Runs validation commands (mvn test ...)
- Self-corrects on failure (max 3 retries with stderr feedback)
- Updates Progress Log + Scenario Map status
- Two-stage commit (`feat:` code + `chore:` metadata)

Adopts harness framework (jha0313/harness_framework) §E with our feature-file
based mapping. See docs/adr/ADR-013-tdd-strategy.md and scripts/README.md.

Git policy: see docs/CONVENTIONS-GIT.md (single source of truth).
PROTECTED_BRANCHES, ensure_feature_branch(), and two_stage_commit() implement
§1, §3 of that document. Any change to git logic here must be preceded by an
update to CONVENTIONS-GIT.md (see scripts/CLAUDE.md item 5).
"""
from __future__ import annotations

import argparse
import datetime as dt
import logging
import os
import re
import shlex
import subprocess
import sys
from dataclasses import dataclass, field
from pathlib import Path
from typing import Optional

REPO_ROOT = Path(__file__).resolve().parent.parent
LOGS_DIR = REPO_ROOT / "logs"
DOCS_DIR = REPO_ROOT / "docs"
FEATURES_DIR = DOCS_DIR / "features"
ADR_DIR = DOCS_DIR / "adr"
CLAUDE_MD = REPO_ROOT / "CLAUDE.md"
ERD_MD = DOCS_DIR / "ERD.md"

MAX_RETRIES = 3
PHASE_TIMEOUT_SEC = 1800  # 30 minutes per phase
PROTECTED_BRANCHES = {"main", "master"}

# ---------------------------------------------------------------------------
# Data structures
# ---------------------------------------------------------------------------


@dataclass
class Phase:
    """A Phase or sub-phase in a feature file's Execution Plan."""
    id: str  # "0", "1", "2", "3", "3.1", "3.2", ..., "4", "5", "6"
    title: str  # full heading line, e.g. "Phase 3.1: Domain layer GREEN"
    body: str  # full markdown body of the phase section
    validation_commands: list[str] = field(default_factory=list)
    ac: str = ""  # Acceptance Criteria text
    is_subphase: bool = False  # True if id contains "."

    @property
    def is_red(self) -> bool:
        return self.id == "2"

    @property
    def is_review(self) -> bool:
        return self.id == "5"


@dataclass
class FeatureFile:
    path: Path
    name: str  # derived from filename (feature-001-idempotency-handling)
    feature_slug: str  # idempotency-handling (for branch name)
    raw_content: str
    phases: list[Phase] = field(default_factory=list)
    applied_adrs: list[str] = field(default_factory=list)
    affected_entities: list[str] = field(default_factory=list)


# ---------------------------------------------------------------------------
# Feature file parser
# ---------------------------------------------------------------------------


def parse_feature_file(path: Path) -> FeatureFile:
    """Parse a feature file, extracting phases and metadata."""
    if not path.exists():
        raise FileNotFoundError(f"Feature file not found: {path}")

    raw = path.read_text(encoding="utf-8")
    name = path.stem  # feature-001-idempotency-handling
    # Extract slug after "feature-NNN-"
    slug_match = re.match(r"feature-\d+-(.+)", name)
    feature_slug = slug_match.group(1) if slug_match else name

    ff = FeatureFile(path=path, name=name, feature_slug=feature_slug, raw_content=raw)

    # Locate Execution Plan section
    exec_plan_start = raw.find("## Execution Plan (TDD)")
    if exec_plan_start == -1:
        raise ValueError(f"No '## Execution Plan (TDD)' section in {path}")
    exec_plan_end = raw.find("\n## ", exec_plan_start + 1)
    if exec_plan_end == -1:
        exec_plan_end = len(raw)
    exec_plan = raw[exec_plan_start:exec_plan_end]

    # Match ### Phase N: ... and #### Phase N.M: ...
    phase_pattern = re.compile(
        r"^(####? )Phase (\d+(?:\.\d+)?)(?:\s*\([^)]*\))?:\s*(.+)$",
        re.MULTILINE,
    )
    matches = list(phase_pattern.finditer(exec_plan))

    for i, m in enumerate(matches):
        phase_id = m.group(2)
        title = f"Phase {phase_id}: {m.group(3).strip()}"
        body_start = m.start()
        body_end = matches[i + 1].start() if i + 1 < len(matches) else len(exec_plan)
        body = exec_plan[body_start:body_end]

        validation_cmds = _extract_validation_commands(body)
        ac = _extract_ac(body)

        ff.phases.append(Phase(
            id=phase_id,
            title=title,
            body=body,
            validation_commands=validation_cmds,
            ac=ac,
            is_subphase="." in phase_id,
        ))

    # Phase 0 Context — extract Applied ADRs and 영향 엔티티
    if ff.phases and ff.phases[0].id == "0":
        phase0_body = ff.phases[0].body
        adr_match = re.search(r"Applied ADRs?\**:\s*(.+)", phase0_body)
        if adr_match:
            ff.applied_adrs = re.findall(r"ADR-\d+", adr_match.group(1))
        ent_match = re.search(r"영향 엔티티[^:]*:\s*(.+)", phase0_body)
        if ent_match:
            ff.affected_entities = [
                e.strip().strip("`")
                for e in re.split(r"[,/]", ent_match.group(1))
                if e.strip()
            ]

    return ff


def _extract_validation_commands(body: str) -> list[str]:
    """Extract validation commands from `**검증 커맨드**:` ```bash ... ``` blocks."""
    cmds: list[str] = []
    pattern = re.compile(
        r"\*\*검증 커맨드\*\*[^\n]*\n+\s*```(?:bash|sh)?\n(.+?)```",
        re.DOTALL,
    )
    for m in pattern.finditer(body):
        block = m.group(1)
        for line in block.splitlines():
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            cmds.append(line)
    return cmds


def _extract_ac(body: str) -> str:
    """Extract AC line from `**AC**: ...`."""
    m = re.search(r"\*\*AC\*\*:\s*(.+)", body)
    return m.group(1).strip() if m else ""


# ---------------------------------------------------------------------------
# Prompt assembly
# ---------------------------------------------------------------------------


def assemble_prompt(
    feature: FeatureFile,
    phase: Phase,
    completed_phases_summary: str = "",
    retry_error: str = "",
) -> str:
    """Build self-contained prompt for headless Claude per ADR-013 + harness §E."""
    parts: list[str] = []

    # 1) CLAUDE.md (root navigation map)
    parts.append("# Repository Guidelines (CLAUDE.md)\n\n" + CLAUDE_MD.read_text(encoding="utf-8"))

    # 2) Conventions — File (단일 소스, ADR-014 헥사고날 패키지 컨벤션 포함)
    conv_file = DOCS_DIR / "CONVENTIONS-FILE.md"
    if conv_file.exists():
        parts.append("\n# Conventions — File\n\n" + conv_file.read_text(encoding="utf-8"))

    # 3) Conventions — Code (단일 소스, port/adapter 네이밍 + 도메인 패턴 cross-ref 포함)
    conv_code = DOCS_DIR / "CONVENTIONS-CODE.md"
    if conv_code.exists():
        parts.append("\n# Conventions — Code\n\n" + conv_code.read_text(encoding="utf-8"))

    # 4) Applied ADRs
    if feature.applied_adrs:
        parts.append("\n# Applied ADRs\n")
        for adr_id in feature.applied_adrs:
            adr_files = list(ADR_DIR.glob(f"{adr_id}-*.md"))
            if adr_files:
                parts.append(f"\n## {adr_id}\n\n" + adr_files[0].read_text(encoding="utf-8"))

    # 5) ERD (only if affected entities exist)
    if feature.affected_entities and ERD_MD.exists():
        parts.append("\n# Domain Model (docs/ERD.md)\n\n" + ERD_MD.read_text(encoding="utf-8"))

    # 4) Previous phase summary (cumulative context)
    if completed_phases_summary:
        parts.append("\n# Previously Completed Phases\n\n" + completed_phases_summary)

    # 5) Current phase body
    parts.append(f"\n# Current Task — {phase.title}\n\n{phase.body}")

    # 6) Retry error (self-correction)
    if retry_error:
        parts.append(
            "\n# Previous Attempt Failed\n\n"
            "The previous attempt failed validation. Stderr below.\n"
            "Analyze the error and correct your approach.\n\n"
            f"```\n{retry_error}\n```\n"
        )

    # 7) Self-contained reminder
    parts.append(
        "\n# Instructions\n\n"
        "- Use ONLY the information above. No external conversation context.\n"
        "- Run the validation commands listed in this phase to verify your work.\n"
        "- Do NOT modify files outside this phase's stated 작성 대상 / scope.\n"
        "- Do NOT create commits — the orchestrator (execute.py) handles git.\n"
    )

    return "\n".join(parts)


# ---------------------------------------------------------------------------
# Headless Claude invocation
# ---------------------------------------------------------------------------


def invoke_claude_headless(prompt: str, log_path: Path, dry_run: bool = False) -> tuple[bool, str]:
    """Invoke Claude in headless mode. Returns (success, stdout)."""
    if dry_run:
        log_path.write_text(prompt, encoding="utf-8")
        logging.info(f"[dry-run] Prompt written to {log_path} (no Claude invocation)")
        return True, "[dry-run] no claude invocation"

    log_path.write_text(prompt, encoding="utf-8")
    try:
        result = subprocess.run(
            ["claude", "-p", prompt],
            capture_output=True,
            text=True,
            timeout=PHASE_TIMEOUT_SEC,
            cwd=REPO_ROOT,
        )
        # Append response to same log
        with log_path.open("a", encoding="utf-8") as f:
            f.write("\n\n--- CLAUDE RESPONSE ---\n\n")
            f.write(result.stdout)
            if result.stderr:
                f.write("\n\n--- STDERR ---\n\n")
                f.write(result.stderr)

        if result.returncode != 0:
            return False, result.stderr or result.stdout
        return True, result.stdout
    except subprocess.TimeoutExpired:
        return False, f"Claude headless invocation timed out after {PHASE_TIMEOUT_SEC}s"
    except FileNotFoundError:
        return False, "claude CLI not found. Install Claude Code first."


# ---------------------------------------------------------------------------
# Validation
# ---------------------------------------------------------------------------


def run_validation_commands(commands: list[str], log_path: Path) -> tuple[bool, str]:
    """Run validation commands sequentially. First failure aborts. Returns (success, combined_output)."""
    if not commands:
        return True, "(no validation commands specified)"

    output: list[str] = []
    for cmd in commands:
        logging.info(f"Running: {cmd}")
        try:
            result = subprocess.run(
                cmd,
                shell=True,
                capture_output=True,
                text=True,
                timeout=PHASE_TIMEOUT_SEC,
                cwd=REPO_ROOT,
            )
        except subprocess.TimeoutExpired:
            msg = f"Command timed out: {cmd}"
            output.append(msg)
            return False, "\n".join(output)

        output.append(f"\n$ {cmd}\n{result.stdout}")
        if result.stderr:
            output.append(f"[stderr] {result.stderr}")
        if result.returncode != 0:
            output.append(f"[exit {result.returncode}]")
            with log_path.open("a", encoding="utf-8") as f:
                f.write("\n\n--- VALIDATION FAILED ---\n")
                f.write("\n".join(output))
            return False, "\n".join(output)

    with log_path.open("a", encoding="utf-8") as f:
        f.write("\n\n--- VALIDATION PASSED ---\n")
        f.write("\n".join(output))
    return True, "\n".join(output)


# ---------------------------------------------------------------------------
# Git operations (two-stage commit)
# ---------------------------------------------------------------------------


def current_branch() -> str:
    r = subprocess.run(
        ["git", "rev-parse", "--abbrev-ref", "HEAD"],
        capture_output=True, text=True, cwd=REPO_ROOT, check=True,
    )
    return r.stdout.strip()


def ensure_feature_branch(feature: FeatureFile) -> None:
    """Create or checkout `feat-<feature-slug>` branch. Abort if on main/master."""
    target = f"feat-{feature.feature_slug}"
    cur = current_branch()
    if cur == target:
        return
    if cur in PROTECTED_BRANCHES:
        # Create new branch from current
        subprocess.run(["git", "checkout", "-b", target], cwd=REPO_ROOT, check=True)
        return
    # Already on a feature branch but different one
    if cur.startswith("feat-") and cur != target:
        raise RuntimeError(
            f"Currently on feature branch '{cur}' but feature wants '{target}'. "
            "Switch manually."
        )
    subprocess.run(["git", "checkout", "-b", target], cwd=REPO_ROOT, check=True)


def two_stage_commit(feature: FeatureFile, phase: Phase, summary: str) -> None:
    """`feat:` code commit + `chore:` metadata commit. Skip empty stages."""
    cur = current_branch()
    if cur in PROTECTED_BRANCHES:
        raise RuntimeError(f"Refusing to commit on protected branch '{cur}'")

    # Stage 1: code changes (anything outside docs/features/)
    code_paths = ["src/", "pom.xml", "build.gradle", "build.gradle.kts", "load-test/"]
    code_paths = [p for p in code_paths if (REPO_ROOT / p).exists()]
    code_added = False
    for p in code_paths:
        r = subprocess.run(["git", "add", "--", p], cwd=REPO_ROOT)
        if r.returncode == 0:
            code_added = True

    if code_added and _has_staged_changes():
        msg = f"feat({feature.feature_slug}): {summary}"
        subprocess.run(["git", "commit", "-m", msg], cwd=REPO_ROOT, check=True)

    # Stage 2: metadata (feature file)
    subprocess.run(
        ["git", "add", "--", str(feature.path.relative_to(REPO_ROOT))],
        cwd=REPO_ROOT,
    )
    if _has_staged_changes():
        msg = f"chore({feature.feature_slug}): phase-{phase.id} done"
        subprocess.run(["git", "commit", "-m", msg], cwd=REPO_ROOT, check=True)


def _has_staged_changes() -> bool:
    r = subprocess.run(
        ["git", "diff", "--cached", "--quiet"],
        cwd=REPO_ROOT,
    )
    return r.returncode != 0  # nonzero means there ARE staged changes


# ---------------------------------------------------------------------------
# Feature file mutation (Progress Log + Status)
# ---------------------------------------------------------------------------


def update_progress_log(feature: FeatureFile, line: str) -> None:
    """Append a line to the Progress Log section."""
    raw = feature.path.read_text(encoding="utf-8")
    log_marker = "## Progress Log"
    log_idx = raw.find(log_marker)
    if log_idx == -1:
        logging.warning(f"No '## Progress Log' section in {feature.path}; skipping update")
        return
    end_idx = raw.find("\n## ", log_idx + 1)
    if end_idx == -1:
        end_idx = len(raw)
    section = raw[log_idx:end_idx]
    new_line = f"\n- {line}"
    new_section = section.rstrip() + new_line + "\n"
    new_raw = raw[:log_idx] + new_section + raw[end_idx:]
    feature.path.write_text(new_raw, encoding="utf-8")


def update_status(feature: FeatureFile, new_status: str) -> None:
    """Update frontmatter Status column."""
    raw = feature.path.read_text(encoding="utf-8")
    status_pattern = re.compile(
        r"(\| )(Draft|Planning|In-Progress|Review|Done)( \|)",
    )
    new_raw = status_pattern.sub(f"\\1{new_status}\\3", raw, count=1)
    # Update Last Updated
    today = dt.date.today().isoformat()
    new_raw = re.sub(
        r"(\|\s*)\d{4}-\d{2}-\d{2}(\s*\|\s*$)",
        f"\\1{today}\\2",
        new_raw,
        count=1,
        flags=re.MULTILINE,
    )
    feature.path.write_text(new_raw, encoding="utf-8")


# ---------------------------------------------------------------------------
# Main orchestration
# ---------------------------------------------------------------------------


def execute_phase(
    feature: FeatureFile,
    phase: Phase,
    completed_summary: str,
    log_path: Path,
    dry_run: bool,
) -> tuple[bool, str]:
    """Run a single phase with self-correction (max 3 retries). Returns (success, summary)."""
    retry_error = ""
    for attempt in range(1, MAX_RETRIES + 1):
        logging.info(f"Phase {phase.id} attempt {attempt}/{MAX_RETRIES}")
        prompt = assemble_prompt(feature, phase, completed_summary, retry_error)

        attempt_log = log_path.parent / f"{log_path.stem}-phase{phase.id}-attempt{attempt}.log"
        ok, claude_output = invoke_claude_headless(prompt, attempt_log, dry_run=dry_run)
        if not ok:
            retry_error = f"Claude invocation failed: {claude_output}"
            continue

        if dry_run:
            return True, "[dry-run] phase prompt assembled"

        # Run validation
        if phase.is_red:
            # RED phase: tests should FAIL
            ok, output = run_validation_commands(phase.validation_commands, attempt_log)
            if ok:
                retry_error = (
                    "RED phase requires tests to FAIL but they all passed. "
                    "Either tests are not exercising new behavior, or production code already exists."
                )
                continue
            return True, f"RED tests fail as expected (attempt {attempt})"

        ok, output = run_validation_commands(phase.validation_commands, attempt_log)
        if ok:
            return True, f"Phase {phase.id} validation passed (attempt {attempt})"
        retry_error = output

    return False, f"Phase {phase.id} failed after {MAX_RETRIES} retries. Last error: {retry_error[:500]}"


def run_feature(
    feature: FeatureFile,
    target_phase_id: Optional[str],
    dry_run: bool,
    interactive: bool,
    push: bool,
) -> int:
    """Top-level orchestration. Returns exit code."""
    LOGS_DIR.mkdir(exist_ok=True)
    timestamp = dt.datetime.now().strftime("%Y%m%d-%H%M%S")
    log_path = LOGS_DIR / f"execute-{feature.name}-{timestamp}.log"

    if not dry_run:
        ensure_feature_branch(feature)

    completed_summary = ""
    target_phases = (
        [p for p in feature.phases if p.id == target_phase_id]
        if target_phase_id
        else feature.phases
    )
    if target_phase_id and not target_phases:
        logging.error(f"Phase {target_phase_id} not found in feature.")
        return 2

    for phase in target_phases:
        # Skip Phase 0 (Context — no execution, just metadata)
        if phase.id == "0":
            continue

        if interactive:
            ans = input(f"\n>>> Run Phase {phase.id} ({phase.title})? [y/N]: ").strip().lower()
            if ans != "y":
                logging.info(f"Skipped Phase {phase.id} per user request.")
                continue

        ok, summary = execute_phase(feature, phase, completed_summary, log_path, dry_run)

        if not ok:
            update_progress_log(
                feature,
                f"{dt.datetime.now().strftime('%Y-%m-%d %H:%M')} — Phase {phase.id} BLOCKED — {summary}",
            )
            update_status(feature, "In-Progress")
            logging.error(summary)
            logging.error("Operator intervention required. Exiting.")
            return 1

        if not dry_run:
            update_progress_log(
                feature,
                f"{dt.datetime.now().strftime('%Y-%m-%d %H:%M')} — Phase {phase.id} done — {summary}",
            )
            update_status(feature, "In-Progress")
            two_stage_commit(feature, phase, summary)

        completed_summary += f"\n## Phase {phase.id}\n{summary}\n"

    if push and not dry_run:
        cur = current_branch()
        subprocess.run(["git", "push", "-u", "origin", cur], cwd=REPO_ROOT, check=True)
        logging.info(f"Pushed to origin/{cur}")

    logging.info("Feature execution complete.")
    return 0


# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Execute a feature file's TDD phases via headless Claude.",
    )
    parser.add_argument("feature_file", type=Path, help="Path to docs/features/feature-NNN-*.md")
    parser.add_argument("--phase", help="Run only this phase (e.g., 1, 3.2). Default: all.")
    parser.add_argument("--dry-run", action="store_true", help="Print prompts only, do not invoke Claude or commit.")
    parser.add_argument("--interactive", action="store_true", help="Prompt user before each phase.")
    parser.add_argument("--push", action="store_true", help="Push branch to origin after success.")
    parser.add_argument("-v", "--verbose", action="store_true", help="Verbose logging.")
    args = parser.parse_args()

    logging.basicConfig(
        level=logging.DEBUG if args.verbose else logging.INFO,
        format="[%(asctime)s] %(levelname)s %(message)s",
    )

    # Safety: block protected branches unless dry-run
    if not args.dry_run:
        try:
            cur = current_branch()
            if cur in PROTECTED_BRANCHES:
                logging.warning(
                    f"On protected branch '{cur}'. Will create feat-* branch on first commit."
                )
        except subprocess.CalledProcessError:
            logging.error("Not in a git repository.")
            return 2

    feature = parse_feature_file(args.feature_file)
    return run_feature(
        feature,
        target_phase_id=args.phase,
        dry_run=args.dry_run,
        interactive=args.interactive,
        push=args.push,
    )


if __name__ == "__main__":
    sys.exit(main())
