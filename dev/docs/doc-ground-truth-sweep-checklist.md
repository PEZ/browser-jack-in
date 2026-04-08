# Documentation Ground Truth Sweep Checklist

Use this when the docs have started to drift from the code and you want a repeatable way to bring them back in line.

## When To Run It

- After a subsystem rename or refactor
- After changing ownership of a flow between files or modules
- After changing runtime behavior that the docs describe as current architecture
- When you find one stale claim and suspect it is not isolated

## Preparation

1. Start from one concrete stale claim.
2. Verify the current implementation before widening the search.
3. Check the active watchers before editing docs:
   - Squint watch clean
   - Unit test watch green
   - Scittle Dev REPL available
4. Keep the sweep scoped to one repo and one round of related drift.

## Audit Plan

Split the audit into non-overlapping slices and run them in parallel when the scope is large enough. A good default split is:

1. Installer and injection docs
2. Connection, bridge, and lifecycle docs
3. Dependency resolution and library docs
4. Popup, panel, storage, and metadata docs
5. Cross-cutting docs such as README and overview pages

For each slice:

1. Read the relevant docs in full.
2. Read the owning source files in full.
3. Search nearby docs only when the first files show likely copy-pasted drift.
4. Verify every suspected mismatch against current code.
5. Do not edit during the audit pass.

## What Counts As Drift

Look for claims that name or describe current behavior incorrectly:

- Stale function names
- Stale file ownership or responsibility claims
- Stale flow order or call-chain descriptions
- Stale state-shape or payload-shape examples
- Stale timing semantics
- Dead links to docs that no longer exist
- User-facing wording in source that contradicts current behavior

Ignore purely historical phrasing unless it is presented as the current design.

## Required Report Format

Each audit slice should report back in a form that can be turned directly into edit packets:

1. Scope summary
2. Files audited
3. Findings, each with:
   - Severity
   - Doc file path
   - Section heading or line clue
   - Exact stale quote
   - Current ground truth with code references
   - Recommended replacement wording
   - Confidence
4. Edit packets grouped by doc file
5. Residual uncertainties

If a file appears in scope but has no issues, say so explicitly.

## Editing Pass

1. Merge overlapping findings.
2. Build non-overlapping edit packets by file.
3. Apply the packets in parallel when they do not touch the same file.
4. Keep wording fixes minimal. Do not use a doc sweep to rewrite unrelated sections.
5. Preserve the existing document voice unless the current voice itself causes ambiguity.

## Validation

After the edits:

1. Run diagnostics on all changed docs.
2. Search for the exact stale phrases that triggered the sweep.
3. Search for close variants of the same stale claim across the repo docs.
4. Check the watchers again if any source strings or code-adjacent docs changed.
5. Summarize what changed and note anything intentionally left for a later pass.

## Good Sweep Boundaries

- Fix what the code disproves.
- Do not invent behavior that the source does not establish.
- Do not silently widen from one subsystem into the whole repo unless the audit findings justify it.
- If a source string in UI code is wrong, fix it in the same round as the docs.

## Output Goal

At the end of the sweep, a reviewer should be able to answer three questions quickly:

1. What claims were wrong?
2. What is the current ground truth?
3. Which files now encode that truth?