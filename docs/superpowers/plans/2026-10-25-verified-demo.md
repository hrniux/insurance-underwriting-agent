# Verified AI Demo Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox syntax.

**Goal:** Deliver one-command offline underwriting acceptance with durable evidence and a researched AI repository shortlist.
**Architecture:** Keep the existing Java application. Add a Python standard-library HTTP validator and a local process launcher, plus repository documentation and research data.
**Tech Stack:** Java 21, Maven wrapper, Bash, Python 3 standard library.
**Spec:** docs/superpowers/specs/2026-10-25-verified-demo-design.md

## Global Constraints
- Default Mock model and fictitious data; no API keys or real LLM quality claims.
- Do not change Java business code, dependency versions or the existing demo UI.
- Default launcher port 18080; occupied ports must fail, no reuse of unrelated services.
- Bounded startup and HTTP waits; nonzero exit and saved failure summary on failure.
- Persist raw HTTP responses and summary under target/demo-evidence/<run-id>.
- Four fixed outcomes: P-1001 MANUAL_REVIEW/HIGH/70, P-2001 APPROVE/LOW/10, P-3001 MANUAL_REVIEW/MEDIUM/40, P-4001 REJECT/HIGH/60.
- Full HTTP verification includes seven successful steps, six tool traces, evidence/provenance, mock model, idempotent replay and conflict, RAG Recall@4/MRR=1, stable replay and report retrieval.
- Mac/Linux portability, no extra Python dependencies.
- Independent branch and draft PR; no merge or public hosting.

## Review Focus
- A stale service at the requested port must not produce a misleading pass.
- Missing/malformed JSON or provenance must fail and preserve evidence.
- Repeated runs use new keys and output directories.
- Launcher terminates only its own process on success/failure/interruption.
- Degraded or real-model service must not claim to pass offline Mock acceptance.

### Task 1: One-command acceptance
**Files:** Create scripts/verify-demo.py, scripts/run-verified-demo.sh, scripts/tests/test_verify_demo.py, docs/VERIFIED_DEMO.md; modify README.md.
**Interfaces:** Existing health, demo/scenarios, underwriting/evaluations, knowledge/evaluations, replay and report REST endpoints. Validator CLI --base-url and --output-dir. Launcher accepts a DEMO_PORT environment override; Maven CLI args may be supplied without editing repo for this cloud proxy.
- [ ] Read existing DTOs and scripts to confirm schemas.
- [ ] Write meaningful behavior tests first: wrong decision/provenance/model must fail, accepted fixed outcomes pass; HTTP failures create non-passing summary. Run and observe missing implementation fail.
- [ ] Implement validator using urllib, json, pathlib and other standard library modules. Save raw responses and final summary with actual commit/time/model source; fail on any unmet contract.
- [ ] Implement launcher: build with wrapper, independent Java process, bounded readiness, forced Mock/default profile, occupied-port rejection, cleanup trap; invoke validator.
- [ ] Run Python tests, Bash syntax checks and launcher against the real application. Expected successful evidence and all checks pass; verify occupied-port failure and repeated runs.
- [ ] Document exact startup/verification commands and limitations in Chinese.
- [ ] Commit only files in scope, report tests and any limitations.

### Task 2: Researched shortlist and delivery (controller)
**Files:** docs/AI_OPEN_SOURCE_TOP20.json, docs/AI_OPEN_SOURCE_TOP20.md.
**Interfaces:** 20 verified GitHub canonical full names, license/readme/release sources, authenticated hrniux Star API.
- [ ] Write ranked shortlist with rationale, tradeoffs and sources; distinguish all-GitHub scope from curated sample.
- [ ] Star each selected repo and re-read each status, preserve results.
- [ ] Verify exactly 20 unique, nonarchived repositories and report any limitation.
- [ ] Run full Java/Python acceptance, fresh branch review, commit and push feature branch, create draft PR. Never merge.
