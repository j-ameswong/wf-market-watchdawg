---
name: humanize-comments
description: Rewrite comments and doc comments (KDoc, Javadoc, JSDoc, docstrings, Rust `///`, Go doc comments) that carry the right information but read badly — aphoristic openers, clauses chained on em-dashes, ticket or requirement IDs wedged mid-sentence, walls of undifferentiated prose, strained metaphors. Preserves every fact; changes only how it reads. Use when the user says comments are hard to read, dense, verbose, florid, over-written, "AI-generated-sounding", or asks to humanize, simplify, or clean up the wording of comments or docs, even if they never use the word "humanize".
---

# Humanize Comments

Some comments hold exactly the right information and are still a chore to
read. This skill fixes the prose. It does not decide which comments should
exist.

That distinction matters, because it decides which skill you want:

| Question | Skill |
|---|---|
| Should this comment exist at all? | `self-documenting-code` |
| This comment should exist — why is it painful to read? | this one |

If comments are redundant, stale, or commented-out code, run
`self-documenting-code` instead. If they are accurate and useful but dense,
you are in the right place. Running both is reasonable: cut first, then
rewrite what survives.

## The one rule that outranks everything

**Never drop a fact to make a sentence nicer.** A comment exists because
someone knew something the code could not say. If you cannot fit a detail
into your rewrite, the rewrite is wrong — add a sentence, not a cut.

Every finished pass should satisfy this check:

```bash
git diff -U0 | grep -E "^[+-]" | grep -vE "^(\+\+\+|---)" \
  | grep -vE "^[+-]\s*(\*|/\*|//|#)" | grep -vE "^[+-]\s*$"
```

Anything it prints is a code line you changed. Expect no output, except
lines that are code with a trailing comment on the same line. If you see a
real code change, you have exceeded the scope of this skill.

## The tics

These are the recurring patterns. Each one is a real failure of reading, not
a matter of taste.

### 1. Aphoristic opener instead of a subject

The first sentence should say what the thing *is*. A riddle that resolves
only after you have read the implementation is not a summary.

```kotlin
// Before
/** What the rate-limit boundary looks like from outside (R12.1). ... */

// After
/** Meters for the rate limiter, so its behaviour can be checked at runtime (R12.1). ... */
```

### 2. Clauses chained on dashes

Three ideas welded into one sentence means the reader cannot stop halfway
and still hold a complete thought. Split into sentences. Reserve the dash
for a genuine aside, at most one per paragraph.

Grep for the chained form directly: `grep -rn -e " -- " -e " — " <path>`.

### 3. IDs wedged mid-clause

Requirement, ticket, ADR, and spec references are necessary, but a reader
who has to resolve `R1.4` to parse the grammar of a sentence has lost the
sentence. Move the reference to the end of the clause it qualifies, in
parentheses.

```kotlin
// Before
/** Route class, not API version (ADR-0005). v1 `statistics` paces on PUBLIC ... */

// After
/**
 * Picks the bucket from the route, not from the API version (ADR-0005).
 *
 * Auction search is the only thing that draws on the contract-search budget (SPEC 2.5).
 * Everything else shares PUBLIC ...
 */
```

### 4. Wall of prose

A doc comment carrying four ideas needs four paragraphs. Lead paragraph:
what it is. Then one idea each — why it is built that way, what the
constraint is, what a future editor must not break.

### 5. Strained vocabulary

Ordinary words, unless the domain genuinely has a term of art. Words that
consistently signal this tic: *seam*, *turnstile*, *blast radius*, *read
point*, *seat*, *surface* (as a verb for "show"), *obliged to*, *reads as*,
*hands out*, *is the numerator of*.

### 6. Cryptic compression

A comment that makes the reader solve something is not saving them time.

```kotlin
// Before
// public is 2 req/s, so N=5 acquires cost (N-1)/L = 2s -- the first turn is free.

// After
// public is 2 req/s and the first turn is free, so 5 acquires cost 4 * 500ms = 2s.
```

### 7. The comment describes a neighbour, not its subject

A comment attached to a type should describe *that type*. When it instead
describes what some other component does with it, re-anchor it.

```kotlin
// Before — the record does not refetch anything; the scheduler does
// Refetch GET /v2/versions only when the hash changes
data class CollectionVersionRecord(...)

// After
// One row per collection, holding the hash last seen, so a tick only refetches what changed.
```

## Be selective

The most common way to do this badly is to rewrite everything. A clear
comment that happens to be short is finished. Touching it adds diff noise
and buries the changes that matter.

In a recent pass over ~40 inline comments, 24 were worth changing and 16
were already fine. That ratio is normal. Leave alone:

- Comments that already lead with a subject and stop at one or two ideas.
- Tool directives (`// noqa`, `@Suppress`, `eslint-disable`) — never touch.
- Licence and attribution headers — never touch.
- Terms of art the domain actually uses.

Say in your report how many you left alone, so it is clear they were
considered rather than missed.

## Workflow

1. **Scope it.** A directory, a package, a diff. Default to the smallest
   scope the request names. Split main sources from tests if the change set
   is large.

2. **Read everything in scope before editing anything.** You cannot tell a
   load-bearing detail from a flourish without knowing what the code does.

3. **Rewrite with exact-match replacements, not regex.** Doc comments are
   full of punctuation that sed will mangle. Use a helper that fails loudly
   when a match is missing or ambiguous:

   ```python
   def edit(path, old, new, count=1):
       s = pathlib.Path(path).read_text()
       n = s.count(old)
       if n != count:
           FAILS.append(f"{path}: expected {count}, found {n}")
           return
       pathlib.Path(path).write_text(s.replace(old, new))
   ```

   Collect failures and report them all at the end rather than aborting on
   the first, so one bad match does not hide the rest.

4. **Watch the line limit.** Rewrites usually get longer. Check the
   project's configured maximum (`.editorconfig`, linter config) and verify
   nothing crossed it:
   `awk 'length>120 {print FILENAME":"FNR}' $(find <path> -name '*.kt')`

5. **Verify.** Run the comment-only diff check above, then the project's
   build and linter. Doc comments are compiled input in some languages and
   a malformed one can fail a build.

6. **Commit separately** from any behaviour change. A comment-only commit
   is trivial to review; mixed with a refactor it is not.

## Accuracy fixes found along the way

Rewriting a comment means reading it against the code, which is the most
reliable way to discover it was wrong. Two real examples from one pass:

- A doc comment said it read "a few hundred bytes". It read exactly 800.
- A test class said "every meter here is read from a registry this test
  owns". One test read the shared registry.

Fix these, and **call them out separately in your report** — they are not
prose changes, and a reviewer skimming a comment-only diff will assume
nothing about the code's meaning changed.

If a comment contradicts the code in a way that suggests the *code* is
wrong, stop and report it. Do not fix it in this pass.

## Report

Finish with a short report:

- Counts: files touched, comments rewritten, comments deliberately left.
- Two or three before/after pairs, chosen as the most representative.
- Any accuracy fixes, listed separately.
- Anything you found that suggests a bug, unfixed and flagged.
- Verification: the comment-only diff check, build, and linter results.
