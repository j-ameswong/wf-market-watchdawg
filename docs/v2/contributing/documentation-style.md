---
sidebar_position: 1
---

# Documentation Style

Use this page as the contributor-facing style guide for Warframe.market public documentation.

This repository documents public contracts: HTTP endpoints, WebSocket messages, OAuth flows, data models, and marketplace/client rules. Changes should be small, evidence-based, and easy to review.

## Contribution Modes

### Wording-Only Changes

Most public contributors will not have access to the private API backend repository. That is fine for wording-only improvements.

Wording-only changes include:

- Grammar, spelling, and punctuation fixes.
- Clearer prose that does not change behavior.
- Formatting and table readability improvements.
- Broken link fixes.
- Typos in headings, labels, or descriptions.

Do not change request/response shapes, fields, enum values, auth requirements, status codes, validation rules, WebSocket routes, scopes, or examples that imply behavior changes without backend source evidence.

### Contract Changes

Contract changes require backend source evidence from maintainers.

Contract changes include:

- HTTP methods, paths, query parameters, headers, request fields, response fields, status codes, errors, sorting, caching, or rate limits.
- Data model fields, types, optionality, enum values, visibility flags, or examples.
- WebSocket routes, payload fields, command responses, event timing, stream metadata, auth, or scopes.
- OAuth scopes, redirect behavior, token lifetimes, refresh/revoke behavior, or error shapes.

Maintainers usually keep the private API repository next to this docs repository as `../Api`. If that repository is unavailable and the change needs backend verification, ask a maintainer instead of guessing.

## Core Principles

- Document implemented public behavior, not expected behavior from memory.
- Read nearby docs before editing so terminology, headings, and style stay consistent.
- Keep diffs small. Prefer targeted edits over broad rewrites.
- Keep headings stable because other pages and future updates may link to them.
- Use tables for structured fields, headers, enum values, model properties, and errors.
- Link reusable data models instead of duplicating large schemas on endpoint or WebSocket pages.
- Use badges and callouts to improve scanning, but never rely on icons as the only meaning.
- Keep examples realistic, short, and maintainable.

## Markdown And MDX

Use `.md` for plain documentation pages.

Use `.mdx` when a page needs shared UI components, such as:

- `ApiEndpoint`
- `WsMessage`
- `ApiBadge`
- `ApiCallout`
- `BadgeGrid`

Do not use old Notion-only formatting such as color spans.

Do not use raw admonition blocks such as:

```md
:::warning
Text
:::
```

Use `ApiCallout` instead when a styled warning, note, or requirement box is needed.

## Code Blocks

Use `json` only for concrete, copyable JSON payloads.

```json
{
  "route": "@wfm|cmd/example/doThing",
  "id": "message-id",
  "payload": {
    "enabled": true
  }
}
```

Use `ts` for schematic shapes that contain model names or type placeholders.

```ts
{
  apiVersion: "x.x.x",
  data: Order[],
  error: null,
}
```

Do not write reusable model placeholders as JSON strings:

```json
{
  "data": ["Order"]
}
```

That reads as an array containing the string `Order`, not an array of order models.

Use `http` for raw HTTP, multipart, or header examples.

```http
form-data; name="avatar"; filename="avatar.png"
```

## HTTP API Pages

Use the detailed format guide: [HTTP API Endpoint Format](./http-api-endpoint-format.mdx).

HTTP endpoint pages should generally use:

- `ApiEndpoint` for the route card.
- `ApiCallout` for auth, restrictions, cache notes, and response context.
- `### Request` and `### Response` as the two main endpoint sections.
- `#### URL Parameters`, `#### Query Parameters`, `#### Headers`, `#### Body`, `#### Body Fields`, `#### Constraints`, `#### Errors`, and `#### Data Fields` where applicable.

Do not include HTTP methods in alternative routes.

## WebSocket Pages

Use the detailed format guide: [WebSocket Message Format](./websocket-message-format.mdx).

WebSocket pages should generally use:

- `WsMessage` for route cards.
- `ApiCallout` for auth, restrictions, and stream notes.
- `Client Message`, `Payload Fields`, `Success Message`, and `Error Message` sections for commands.
- Event examples plus `Payload Fields` when payloads are inline.
- `ts` blocks for schematic model payloads such as `payload: Order`.

Do not document WebSocket routes that are constants only. Confirm they are registered and implemented.

## Data Models

Data models live at [Data Models](../data-models.mdx).

Model docs should:

- Use stable `## ModelName` headings.
- Use tables with `Field`, `Flags`, `Type`, and `Description`.
- Use badges for `optional/contextual`, `owner/self`, `moderator+`, and `first-party` flags.
- Link model sections from HTTP and WebSocket docs.
- Avoid duplicating full model schemas in endpoint pages.

Maintainer source of truth, when available, is `../Api/src/internal/wfm/entity_*.json.go`.

## Verification

Run the smallest relevant check for the change.

For docs content, sidebar, MDX, or links:

```bash
yarn build
```

For TypeScript, React components, Docusaurus config, or shared UI changes:

```bash
yarn typecheck
```

If verification is skipped, explain why and name the command that should be run.

## Review Checklist

Before opening a PR or handing off a change, check:

- Does this change alter public behavior? If yes, is backend source evidence available?
- Are examples either valid JSON or intentionally marked as `ts` shapes?
- Are reusable models linked instead of copied?
- Are headings stable and specific?
- Are badges text-first and accessible?
- Are new pages included in `sidebars.ts`?
- Did `yarn build` pass, or is there a clear reason it was not run?
