# Pull Request Template
## Description

<!-- Briefly describe the change and why it's needed. Link any related issues or
discussions. -->

## Type of change

<!-- Select the type that best describes the PR. The PR title must match
(conventional commit format). See AGENTS.md for details. -->

- [ ] feat: — new feature (minor bump)
- [ ] fix: — bug fix (patch bump)
- [ ] perf: — performance improvement (patch bump)
- [ ] feat!: / fix!: — breaking change (major bump)
- [ ] docs: — documentation only (no release)
- [ ] chore: — maintenance (no release)
- [ ] style: — formatting (no release)
- [ ] refactor: — code restructure (no release)
- [ ] test: — test changes (no release)
- [ ] ci: — CI/CD changes (no release)
- [ ] build: — build system (no release)

## Checklist

- [ ] I have read AGENTS.md and followed the workflow
- [ ] The PR title follows conventional commit format
- [ ] `nix flake check` passes
- [ ] `sbt testFull` passes
- [ ] If adding code: relevant tests are included
- [ ] If changing the API: wire formats are pinned in tests
