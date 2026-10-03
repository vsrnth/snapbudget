# Agent Collaboration Policy

## Tool execution and Codemode

- For all future work in this repository, use Codemode (`codemode`) for tool execution and orchestration whenever the needed tools are available through it.
- Batch independent tool calls in one Codemode execution and keep dependent steps sequential.
- Tools not exposed through Codemode, such as collaboration tools, may be called directly as required by their API.

## Model roles

- Use `gpt-6.1-sol` as the primary/root orchestrator.
- Use `gpt-6-luna` subagents exclusively for all implementation and code-writing tasks. All production code, test code, scripts, migrations, configuration code, and code modifications must be authored by Luna subagents.
- The Sol orchestrator must not write or modify code directly. Sol owns task decomposition, architectural decisions, assignment boundaries, orchestration, integration review, validation, and commits.
- Give each Luna subagent a concrete, bounded task with explicit files or module ownership. Avoid overlapping write scopes between agents.
- If `gpt-6-luna` is unavailable, tell the user before substituting another model. Do not silently change the requested model.

## Working agreement

1. Inspect the relevant code and repository instructions before assigning work.
2. Keep pure Kotlin domain and application logic independent of Android/Compose, OCR, and Room or other persistence implementations.
3. Delegate every implementation and code-writing task to Luna subagents, whether or not parallel execution is useful. Sol may inspect code and request revisions, but Luna must author every code change.
4. Have Sol review all resulting diffs for architecture, correctness, security, and unintended changes.
5. Run formatting checks, Android Gradle builds, lint, focused unit tests, and instrumentation/Compose tests as appropriate before committing.
6. Target Android runtime only, using Kotlin and Compose, with the current minimum SDK of 26; desktop targets are out of scope.
7. Keep OCR, parsing, and expense storage on-device. Do not add server or account requirements, telemetry, uploads, or the INTERNET permission; use a bundled OCR model that works offline, keep private data local, and disable cloud backup. Save expenses only after user confirmation and protect against duplicate imports. Never log or commit screenshots or sensitive OCR content, and do not perform automatic external writes.
8. Keep commits granular and Mitchell-style: each commit should contain one coherent, independently reviewable and validated change where practical, use an imperative conventional-style subject, avoid unrelated cleanup or mixed milestones, and separate policy or documentation-only changes when sensible. Sol is the only agent that creates commits; Luna workers must not commit.
9. Every UI regression fix or new UI behavior must add deterministic local Android emulator/device Compose UI test coverage where feasible. Keep these tests local-only (never CI), fixture-based, free of network access and real financial data, and use semantic accessibility assertions for behavior and identity plus bounded visual assertions for geometry and rendering.

## Agent skills

### Issue tracker

Issues and PRDs are tracked in GitHub Issues.

### Triage labels

Use the default canonical triage labels.

### Domain docs

This is a single-context offline PhonePe expense-tracking application. Keep OCR, receipt parsing, expense review, local storage, and UI concerns separated.

## Gemini Android Studio Agent Mode

Gemini Agent Mode may be used only as a read-only diagnosis and inspection assistant, including exploration of a dedicated synthetic-only emulator. It may inspect source and report findings or recommendations, but must not edit files, generate/apply patches, change dependencies or configuration, or commit. Follow [docs/gemini-agent-mode.md](docs/gemini-agent-mode.md) for onboarding, privacy, device boundaries, and prompts. Ask for explicit approval before any shell command, build, deploy, or device interaction because these can have local side effects. Never provide real financial data, sensitive/real-receipt OCR content, or screenshots, logs, or other outputs containing private data. Synthetic-only diagnostic outputs may be shared only with explicit human approval. `.aiexclude` is context control, not a sandbox or a guarantee against explicit sharing.

Codemode requirements above apply to Pi tools when available; they do not imply that Gemini has Pi tools or can invoke them. Preserve Sol orchestration and Luna-only code authorship: Gemini recommendations go to Sol for review and, if approved, implementation by Luna.
