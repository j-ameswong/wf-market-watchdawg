---
name: self-documenting-code
description: Review a codebase, directory, file, or diff and refactor it toward self-documenting code (clear names, small well-named functions, named constants, explicit types) while removing redundant, verbose, outdated, and commented-out comments. Keeps the comments that explain *why*. Use this skill whenever the user asks to clean up or reduce comments, declutter code, improve readability or naming, "make this self-documenting", review code quality, or mentions over-commented, noisy, or AI-generated-looking comments, even if they never use the phrase "self-documenting".
---

# Self-Documenting Code

The goal is code that explains itself, with comments reserved for what code
cannot say. The target is **not** zero comments. It is that every comment that
remains earns its place.

Keep one asymmetry in mind throughout: deleting a comment that carried real
information is a worse outcome than leaving a redundant one. So the job is not
"remove comments", it is "move information into the code where possible, and
remove comments only once their information lives somewhere better, or never
existed".

## The core test

For every comment, ask: **"If I deleted this, what would a competent reader of
this language lose?"**

| Answer | Action |
|---|---|
| Nothing; the code already says it | Delete |
| Something the code *could* express | Refactor so the code expresses it, then delete |
| Something the code *can't* express (why, constraints, external context) | Keep, and tighten if it's wordy |

"Competent reader" matters. Don't write comments for someone who doesn't know
the language, and don't delete comments that explain genuinely unusual
domain knowledge (tax rules, protocol quirks, physics) just because a
programmer can read the syntax.

## Workflow

### 1. Establish scope and mode

Work out two things before touching anything:

- **Scope**: whole repo, one directory, specific files, or only the lines in a
  diff/PR. Default to the smallest scope that matches the request.
- **Mode**: *review* (produce a report, change nothing) or *apply* (edit the
  files). If the user asked to "look over" or "review", default to review. If
  they asked to "clean up" or "fix", apply. If it's ambiguous and the change
  set is large, ask.

Then survey the project:

- For anything larger than a handful of files, run the scanner to find where
  to look first (see [Using the scanner](#using-the-scanner)).
- Look for style rules that override this skill: linter configs requiring
  docstrings (pydocstyle, `eslint-plugin-jsdoc`, Checkstyle Javadoc rules,
  `golint`/`revive` exported-comment rules), a `CONTRIBUTING.md`, or an
  explicit style guide. **Project rules beat this skill.** If the linter
  demands a docstring on every public function, keep them and make them
  better instead of deleting them.
- Find out how to run the tests, linter, and type checker. You'll need them
  in step 4.
- Skip generated code, vendored/third-party code, and migrations. Their
  comments aren't yours to edit and changes get overwritten.

### 2. Classify each comment

Put every comment in the scope into one of three buckets.

#### Bucket A: Remove

| Kind | Example | Why it goes |
|---|---|---|
| Restates the code | `i += 1  # increment i` | Zero information; doubles reading time |
| Commented-out code | `// oldTotal = calc(x);` | Version control remembers it. Nobody knows if it's safe to restore |
| Journal / changelog | `# 2021-03-02 fixed rounding bug` | That's what `git log` is for |
| Byline / attribution | `// Added by Priya` | That's what `git blame` is for |
| Closing-brace markers | `} // end for` | Symptom of a block that's too long. Shorten the block instead |
| Mandated noise | `/** Constructor. */`, `@param name the name` | Repeats the signature word for word |
| Section banners | `######## HELPERS ########` | Often means the file wants splitting; if the sections are genuinely separate concerns, suggest that |

Two cautions for this bucket:

- **Commented-out code that looks deliberately parked** (e.g. accompanied by
  "re-enable once the v2 API ships") isn't noise. Replace it with a TODO that
  keeps the reason, or flag it for the user.
- **A comment that contradicts the code** might mean the *code* is wrong,
  not the comment. Never silently delete these. Flag them in the report as a
  possible bug.

#### Bucket B: Refactor, then remove

These comments contain real information, but it belongs in the code.

| Comment smell | Refactoring |
|---|---|
| Explains what a variable holds | Rename the variable |
| Explains a literal number or string | Extract a named constant |
| Describes what a block of code does | Extract a function whose name is that description |
| Explains a complicated boolean condition | Introduce an explaining variable or a predicate function |
| Explains an argument at a call site: `send(msg, True)  # retry` | Use a keyword argument, named parameter, or enum |
| Records a type: `# list of user IDs` | Add a type annotation, if the language supports it |
| Explains units: `timeout = 30  # seconds` | Put the unit in the name (`timeout_seconds`) or use a duration type |

Example of the most common case, turning a "what" comment into a function:

```python
# Before
def checkout(cart, user):
    # Apply a 10% discount for members with more than 5 orders
    if user.is_member and len(user.orders) > 5:
        cart.total *= 0.9
    ...

# After
LOYAL_MEMBER_MIN_ORDERS = 5
LOYAL_MEMBER_DISCOUNT = 0.10

def checkout(cart, user):
    if is_loyal_member(user):
        cart.total *= 1 - LOYAL_MEMBER_DISCOUNT
    ...

def is_loyal_member(user):
    return user.is_member and len(user.orders) > LOYAL_MEMBER_MIN_ORDERS
```

The comment's information survived: it now lives in three names that the
compiler, the IDE, and every future reader can see. And unlike the comment,
the names can't silently drift out of date, because changing the rule means
touching them.

Don't extract a function for a one-line expression that's already clear.
A good name has to say something the code didn't; if the best name you can
think of just paraphrases the body, the body was fine.

#### Bucket C: Keep (tighten if wordy)

| Kind | Example |
|---|---|
| **Why**: rationale for a non-obvious choice | `# Insertion sort: n is always < 16 here and we need stability.` |
| **Warnings** about consequences | `// Not thread-safe; callers must hold renderLock.` |
| **Workarounds**, ideally with a link | `# Works around pandas#12345; remove after upgrading to 3.x` |
| **Public API docs** (docstrings, JSDoc, Javadoc, Rust `///`, Go doc comments) | These serve readers who never open the implementation. Tighten them; don't delete them |
| **Actionable TODO/FIXME** | `# TODO(#482): paginate once the endpoint supports it` |
| **Dense notation**: regex, bit tricks, maths, algorithm references | `# Luhn checksum, see https://en.wikipedia.org/wiki/Luhn_algorithm` |
| **Legal/license headers** | Never touch |
| **Tool directives** (`# noqa`, `# type: ignore`, `// eslint-disable-next-line`, `#pragma`) | These are instructions to tools, not humans. Never touch unless asked |

Stale TODOs with no owner, ticket, or reason are a judgement call. List them
in the report rather than deleting them yourself.

**Tightening** means cutting words, not information:

```python
# Before
# This function is used to calculate the checksum. We are using CRC32
# here instead of MD5 because we found that MD5 was too slow for the
# large files we process, and we don't need cryptographic security
# since this is only for detecting accidental corruption.

# After
# CRC32, not MD5: much faster on large files, and we only need to detect
# accidental corruption, not tampering.
```

For docstrings, remove the parts that restate the signature (types already
in annotations, "the name" for a parameter called `name`) and keep what the
signature can't say: preconditions, side effects, exceptions raised, units,
edge-case behaviour.

### 3. Beware of over-correcting

Signs you've gone too far:

- A reader now needs to jump between four tiny functions to follow logic that
  used to be eight readable lines.
- Names have grown into sentences (`calculate_total_price_after_applying_member_discount_and_tax`).
  A long name is often a sign the function does too much, which is a
  different problem.
- You removed a comment explaining something surprising, because *you*
  understood it. The test is the future reader, not you.

Context also changes the right comment density:

- **Teaching code, tutorials, and examples** legitimately explain "what",
  because the reader is learning the language. Leave them alone unless asked.
- **Coursework** may have a marking rubric that requires comments. If the
  code looks like an assignment, ask before stripping comments.
- **Language conventions** differ: Go expects doc comments on every exported
  identifier; Python follows PEP 257 for docstrings; many Java shops require
  Javadoc on public methods. Follow the convention of the language and project.

### 4. Apply changes safely (apply mode only)

The whole point of these refactorings is that they don't change behaviour.
Protect that:

- **Only make behaviour-preserving changes.** Renames, extractions, and
  comment edits. If you spot a bug, report it; don't fix it in the same pass
  unless asked, because mixing the two makes the diff impossible to review.
- **Treat public names as a contract.** Renaming exported functions,
  classes, public fields, API parameters, database columns, or anything
  serialised (JSON keys, config keys) can break code you can't see: other
  repos, stored data, callers using keyword arguments. Rename private and
  local names freely; for public ones, *propose* the rename in the report.
- **Find every reference** when renaming. Prefer language tooling
  (IDE/LSP rename, `rope`, `ts-morph`) over find-and-replace, then grep for
  string-based references too: reflection, templates, dependency-injection
  config, `getattr`, test fixtures.
- **Don't reformat unrelated code.** It buries the meaningful changes.
- **Verify.** Run the tests, linter, and type checker afterwards. If there
  are no tests, say so plainly in the report and stay conservative: comment
  removals and local renames only, no function extraction.
- **Keep the diff reviewable.** Work file by file. Where practical, keep pure
  comment deletions separate from refactors (separate commits if you're
  committing), since the first is trivial to review and the second isn't.

### 5. Report

Always finish with a report, in both modes. Use this structure:

```markdown
# Self-documenting code review: <scope>

## Summary
<2-3 sentences: overall comment health, biggest patterns found, what changed>

## Changes made            (apply mode; "Suggested changes" in review mode)
- Removed: <count> redundant comments, <count> blocks of commented-out code, ...
- Refactored: <each rename/extraction, file:line, one line of why>

## Needs your decision
- Comments that contradict the code (possible bugs), with file:line
- Proposed renames of public names
- Stale TODOs, parked code with unclear status

## Deliberately kept
<brief note on the categories of comments left in place and why, so the
user can see they weren't overlooked>

## Verification
<tests/linter/type-check results, or an explicit note that none exist>
```

In review mode, show a short before/after snippet for the two or three most
representative suggestions. Concrete examples persuade far better than a
list of line numbers.

## Using the scanner

`scripts/scan_comments.py` ranks source files by comment density and flags
lines that look like commented-out code. It's for triage in larger codebases:
it tells you where to look first, not what to delete. Every flag needs
judgement, because the heuristics produce false positives (e.g. an English
comment that happens to start with "return").

```bash
python scripts/scan_comments.py <path>              # table of the top 20 files
python scripts/scan_comments.py <path> --top 50     # show more files
python scripts/scan_comments.py <path> --json       # machine-readable output
python scripts/scan_comments.py <path> --min-lines 30   # ignore tiny files
```

What it reports per file:

- **comment %**: comment lines as a share of comment + code lines.
  Documentation comments (docstrings, `/** */`, `///`) and tool directives
  are counted separately and don't inflate this number, since they're usually
  things to keep.
- **dead?**: count of lines that look like commented-out code, with line
  numbers listed below the table.

Roughly, a comment share above ~25-30% in non-teaching code is worth a look,
but treat that as a prompt to read the file, not a verdict. Supported
languages: Python (accurate, via the tokenizer), plus a line-based heuristic
for C-family languages (C, C++, C#, Java, JS/TS, Go, Rust, Kotlin, Swift,
PHP, Scala, Dart), `#`-comment languages (Ruby, shell, R, Perl), and
`--`-comment languages (SQL, Lua, Haskell).
