---
status: accepted
date: 2026-08-07
decision-makers: ajkatz
---

# Repeat fields ride GPSHARE2 additively, and the chunk travels while the target does not

## Context and Problem Statement

Repeatable goals shipped in 0.5.0 without any share-code support, so a shared
plan containing them imported as ordinary one-shots - silently, looking
correct. "Share your daily routine" was part of the original rationale for the
feature, so the gap is real rather than cosmetic.

[ADR-0002](0002-gpshare2-carries-cross-section-dependency-edges.md) froze the
GPSHARE2 wire at the 0.3.0 release, and codes now circulate.

## Decision Drivers

* Codes already in the wild must keep decoding. A version bump would strand
  every archived share code.
* A derived goal's `targetValue` is `senderCurrentValue + chunk` - an absolute
  number meaningful only on the sender's account. Transmitting it would give
  the recipient a target computed from someone else's progress.
* A share code is untrusted input from another client, possibly a newer one.
* The MCP crafts codes independently and must agree exactly (ADR-0002).

## Considered Options

* Additive `repeatEvery` + `repeatChunk` on the existing v2 goal entry
* A GPSHARE3 version carrying the repeat block
* Leave repeatable goals unshareable

## Decision Outcome

Chosen option: "Additive `repeatEvery` + `repeatChunk`".

Two fields on `GoalShareDto`. Older decoders ignore unknown fields and import
the goal as non-repeating, which is the correct degradation - the goal still
works, it just does not repeat.

**`repeatChunk` travels; the re-based `targetValue` does not.** The recipient's
target is computed from their OWN progress on import, which is the same
re-basing rule the rollover uses ([ADR-0005](0005-derived-goals-re-base-their-target-instead-of-storing-a-baseline.md)).
Sending the sender's absolute target would hand over a number derived from
their account.

An unrecognised period - absent, misspelled, or added by a future version -
imports as `NONE` rather than failing the bundle.

### Consequences

* Good, because every circulating GPSHARE2 code still decodes unchanged.
* Good, because a shared daily routine arrives sized to the recipient.
* Bad, because an older client imports a daily as a one-shot with no warning.
  Accepted: the alternative is refusing the code outright, which is worse.
* Bad, because two repos must ship the field together or codes crafted by the
  MCP lose their repeat data in one direction.

### Confirmation

`ShareMapperTest` pins that a repeatable goal exports its period and chunk,
that a plain goal exports `NONE`/0, and that a pre-feature goal with a null
period exports `NONE` rather than null. The MCP side needs matching coverage.

## More Information

Binds `goalplanner-share-mcp` - see ADR-0002 for the same obligation on the
cross-edge field.
