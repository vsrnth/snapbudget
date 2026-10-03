# Gemini Android Studio Agent Mode: bounded read-only use

Gemini Agent Mode is an optional assistant for diagnosis and inspection, not an implementation agent. Keep SnapBudget's Sol orchestration and Luna-only code authorship model: Gemini may describe evidence and recommend a change; Sol reviews the recommendation and assigns approved code changes to Luna. Gemini must not edit files, generate/apply patches, change dependencies/configuration, or commit. Do not treat an agent's proposed fix as permission to implement it.

SnapBudget's product UI is not implemented, and the launcher activity named in the manifest is missing. This setup does not make the app runnable or authorize inventing a product screen. See [testing](testing.md) for actual harness coverage and limits.

## Manual onboarding

1. Install/update Android Studio and open this repository. In Android Studio's Agent pane, select Gemini Agent Mode and review the current [Agent Mode guide](https://developer.android.com/studio/gemini/agent-mode/). The pane and controls may differ by Studio/Gemini version.
2. Check that the session has the repository guidance in context, especially `AGENTS.md` and this document. If it does not, provide those files as context manually. Do not assume `.aiexclude` automatically loads instructions.
3. Review the current [agent files](https://developer.android.com/studio/gemini/agent-files) support and scope. This repository's `AGENTS.md` is project policy; confirm what the installed version actually imports before relying on automatic discovery.
4. Configure the narrowest available permissions and sandbox restrictions for the session using Android Studio's current [permissions guidance](https://developer.android.com/studio/gemini/permissions). Controls and enforcement vary by version; where a reliable no-write restriction is unavailable, do not use Agent Mode for actions or rely on prompting as a technical barrier. Deny file-write and patch-application permission always. Deny terminal/shell, build, deployment, and device permissions by default; only consider a one-time, narrowly scoped action after human review and explicit approval. Approval does not allow edits, patches, dependency/configuration changes, or commits.
5. Review Android Studio's manual [data and privacy settings](https://developer.android.com/studio/gemini/data-and-privacy) and the applicable provider/account terms before sharing project context. Settings, retention, training controls, and availability can vary by product version, account, region, and plan. If offered and appropriate, a paid plan or API-key arrangement may provide stronger training-use terms; verify the terms that apply to your account. Do not infer local processing or confidentiality from any setting or plan.
6. Read `.aiexclude` and Google's [`.aiexclude` reference](https://developer.android.com/studio/gemini/aiexclude). The file narrows context selection using ignore-style patterns. It is not a sandbox, access-control boundary, secret scanner, or protection against a user explicitly attaching a file, screenshot, image, or tool output. It does not guarantee comprehensive secrecy.

Gemini runs as a host-side service and may use internet access; this does not change or establish the network state of an emulator or the app. Do not equate the app's offline design with an offline Gemini session.

## Device and data boundary

- Use only a dedicated emulator containing synthetic fixtures. Before device exploration, manually enable airplane mode and verify Wi-Fi is off. Do not enable automatic device networking, boot/manipulate devices, use cloud device streaming, or provide real financial data.
- Ask for explicit human approval before any shell command, build, deploy, or device interaction. These actions can have local side effects. A prompt is not approval; wait for approval before proceeding. Even when approved, stay within the approved action and synthetic-only boundary.
- Never share real receipt images/OCR output, financial records, personal data, or screenshots/logs/tool output containing private or real-user content. A screenshot or filtered Logcat excerpt from a verified synthetic-only emulator may be inspected only after explicit human approval for that specific capture/output; first verify the dedicated emulator contains only synthetic fixtures. Keep captures transient and narrowly scoped: never save or commit them to the repository. Do not ask Gemini to upload, save, or otherwise externally write data.
- Do not use a physical device containing personal/financial data. Do not use an online emulator, cloud device, or real receipt to make a diagnosis easier.
- `.aiexclude` filters eligible project context; it does not block explicit screenshot capture, Logcat/tool output, or transmission to Gemini. Treat any device/tool capture as an independent sharing action requiring the approval and synthetic-only checks above.

The repository's runner remains the authority for local checks. Use the documented commands in [README](../README.md) and [testing](testing.md), such as `scripts/test-local.sh doctor`, `host`, `unit`, and `device --serial <serial>` where appropriate. `device`/`all` are local-only and reject CI; use only a manually prepared synthetic emulator that is already offline (airplane mode on, Wi-Fi off). The runner does not boot a device or change networking. Build dependency downloads may use host network access. Do not invoke these commands through Gemini without explicit approval. There is no CI run or device streaming for this workflow.

Findings should lead to Sol review, then approved Luna implementation, then deterministic local Compose regression tests with synthetic fixtures and semantic assertions where feasible. Gemini exploration does not replace Compose tests or product journey coverage. Android Journeys are deferred: screenshots are sent to Gemini and AI verification is nondeterministic. No claim is made that Journeys are installed or that the product UI exists.

## Suggested prompts

Paste a prompt only after verifying the session context and permissions. Prompts constrain intent, but do not technically prevent actions if permissions allow them.

### Read-only launch readiness

```text
Read-only inspection only. Do not edit files, generate or apply patches, change dependencies/configuration, run shell commands, build, deploy, interact with a device, or commit. Do not request or transmit screenshots, logs, OCR content, financial data, or other private data. Inspect the repository guidance and relevant source already available in context. Report whether this app appears launch-ready, citing concrete file paths and evidence; distinguish confirmed facts from uncertainty. Do not invent missing product behavior. Ask for explicit human approval before any shell/build/deploy/device action; otherwise finish with recommendations only.
```

### Future synthetic fixture UI audit

Use only after a real UI and dedicated synthetic-only emulator are available, with the emulator already offline. Verify the emulator contains only synthetic fixtures before using it. Obtain explicit human approval before any shell/build/deploy/device action and separately before capturing or sharing a narrowly scoped screenshot or filtered Logcat output. Do not use real receipts or private data. Keep approved captures transient; never save or commit them to the repository. `.aiexclude` does not prevent screenshot or tool-output transmission.

```text
Read-only UI audit using only the approved dedicated synthetic-fixture emulator. Do not edit files, generate/apply patches, change dependencies/configuration, or commit. Before any build, deploy, shell command, or device interaction, stop and ask for explicit human approval. Before capturing or sharing any screenshot or filtered Logcat excerpt, verify the emulator contains only synthetic fixtures and ask for separate explicit approval for that specific output. Never capture/share real OCR content, financial/private data, or output that may contain it. Keep approved captures transient; do not save or commit them to the repository. Inspect only the approved fixture flow and report semantic identity/behavior observations, reproducible steps, and bounded geometry/rendering issues. Separate observations from recommendations; do not claim AI verification is deterministic and do not replace local Compose regression tests. No changes or patches.
```
