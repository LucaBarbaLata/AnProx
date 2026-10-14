# AnProx documentation

Start with the [questionnaire](questionnaire.md). Every decision in the other documents traces
back to one of its 40 answers, cited as **Q1–Q40**.

## For everyone

- [Questionnaire](questionnaire.md): the 40 kick-off decisions (scope, tech, security, design, process)
- [Requirements](requirements.md): what the app must do, numbered (FR-/NFR-) and mapped to milestones
- [Roadmap](roadmap.md): milestones M0 to M9 and what each delivers
- [API coverage](api-coverage.md): which Proxmox VE / Backup Server features are implemented
- [Security](security.md): how credentials are stored, the TLS switch, the threat model
- [Glossary](glossary.md): Proxmox and Android vocabulary

## For developers (and people learning Android)

- [Getting started](getting-started.md): install the tools, build, run, debug
- [Architecture](architecture.md): modules, layers, data flow, and a step-by-step recipe for new features
- [Design system](design-system.md): Midnight Glass tokens, components, motion and accessibility
- [Testing](testing.md): unit and integration tests, the mock server, the manual checklist
- [Decision records (ADRs)](adr/README.md): the reasoning behind each major technical choice
- [Dev log](devlog.md): what happened in each development session, including problems and workarounds
- [Third-party licenses](third-party/)

## Conventions

- Docs are Markdown and live next to the code, so they change in the same commits as the code.
- When a decision changes, add an ADR (or amend the questionnaire's "Amendments" section).
  Don't silently edit history.
- When a feature lands, update [api-coverage.md](api-coverage.md), [roadmap.md](roadmap.md) and
  [CHANGELOG.md](../CHANGELOG.md) in the same pull request.
