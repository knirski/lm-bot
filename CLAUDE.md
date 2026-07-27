# CLAUDE.md

**Read [AGENTS.md](AGENTS.md) — it is the canonical agent guide for this repo and
applies to you in full.** It is kept as one file rather than two so the two
cannot drift; duplicated instructions are how this project has acquired most of
its bugs.

Only Claude Code specifics live here.

## Workflow

This repo is built through the **superpowers** skills. Work is spec → plan →
implement, and the artefacts are committed:

- `docs/superpowers/specs/` — the design (source of truth)
- `docs/superpowers/plans/` — the roadmap and numbered implementation plans
- `docs/superpowers/reports/` — completion reports, reviews, spike findings

Before creative work invoke `superpowers:brainstorming`; before writing a plan,
`superpowers:writing-plans`; to execute one, `superpowers:subagent-driven-development`
or `superpowers:executing-plans`. For a bug, start with
`superpowers:systematic-debugging` rather than guessing.

Plans are written for an engineer with no context and contain literal code, so
follow the plan's code rather than improvising — and if the plan is wrong, fix
the plan (and the spec, if the error originates there) instead of silently
diverging.

## Browser verification

Several frontend bugs here were invisible to the compiler, the linker, and a
fully green test suite. When frontend behaviour matters, drive a real browser.
`agent-browser` is packaged in nixpkgs, so no npm install or `patchelf` is
needed on this NixOS machine:

```bash
nix shell nixpkgs#agent-browser nixpkgs#chromium -c agent-browser --version
```

The `agent-browser` skill's own docs are served by the CLI
(`agent-browser skills get core`).

## Long-running commands

A full re-resolve after wiping `target/` takes several minutes, and first
Chromium launch takes ~70s. Run these in the background and wait on a condition
rather than polling blindly.

Two local aliases will mislead you: `ps` is `procs` and `du` is `dust`. Also
`pgrep -f <pattern>` matches your own shell's command line, so filter it out
before concluding a process is still running.
