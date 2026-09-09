---
sidebar_position: 7
---

# wfm-docs

[GitHub Repository](https://github.com/42bytes-team/wfm-docs)

`wfm-docs` is the public documentation site for Warframe.market. It documents the HTTP API, WebSocket API, OAuth 2.0 plans, shared data models, marketplace rules, and contributor guidance. Built with Docusaurus, React, TypeScript, and Yarn 4.

This is the repository you are reading right now.

## Good Contributions

- Fix grammar, typos, or awkward wording in existing documentation.
- Improve formatting, broken links, or outdated examples.
- Add missing documentation for new API endpoints, WebSocket events, or data model fields.
- Add new documentation pages and register them in `sidebars.ts`.
- Improve the contributor guides or documentation style rules.
- Translate docs into new languages via Docusaurus i18n.

Small, focused pull requests are easier to review than large rewrites.

## Before You Start

You should be comfortable with at least some of:

- [Markdown](https://www.markdownguide.org/)
- [Docusaurus](https://docusaurus.io/)
- [TypeScript](https://www.typescriptlang.org/)
- [Yarn](https://yarnpkg.com/)

Read the [Documentation Style](./documentation-style.md) guide before making changes.

## Requirements

- Node.js 20 or newer.
- Yarn 4.14 or newer.
- Corepack, recommended for using the Yarn version declared in `package.json`.

If Yarn 4 is not already available, enable Corepack first:

```bash
corepack enable
```

## Running Locally

Install dependencies:

```bash
yarn install
```

Start the local docs site:

```bash
yarn start
```

This starts Docusaurus on `localhost:3000` and opens it in your browser.

**Internal Warframe.market developers** who run the full local Docker stack with Nginx proxying should use:

```bash
yarn start:wfm
```

This starts Docusaurus on `0.0.0.0:3001` and opens `http://docs.warframe.test/docs/intro` when the server is ready.

Build the static production site:

```bash
yarn build
```

## Repository Layout

| Area                   | Purpose                                                       |
| ---------------------- | ------------------------------------------------------------- |
| `docs/`                | Main documentation pages. Most content changes happen here.   |
| `docs/api/`            | HTTP API documentation.                                       |
| `docs/websockets/`     | WebSocket command, event, and payload documentation.          |
| `docs/oauth/`          | OAuth 2.0 documentation.                                      |
| `docs/contributing/`   | Contributor guides and documentation style rules.             |
| `src/`                 | Docusaurus pages, React components, and custom CSS.           |
| `static/`              | Static assets copied into the final site.                     |
| `sidebars.ts`          | Sidebar navigation order. Update this when adding docs pages. |
| `docusaurus.config.ts` | Docusaurus site configuration.                                |

## How To Contribute With Git

1. Fork the repository.
2. Create a branch for your topic.
3. Edit the relevant files in `docs/`.
4. If you add a new page, register it in `sidebars.ts`.
5. Run `yarn build` for docs, sidebar, link, or MDX changes.
6. Run `yarn typecheck` for TypeScript, React, or Docusaurus config changes.
7. Submit a pull request with a short explanation of what you changed.

## Public Contributor Rules

Most public contributors do not have access to the private API backend repository. Without backend source evidence, only make wording-only improvements such as grammar, clarity, formatting, or typo fixes.

Do not change documented public behavior. This includes request or response shapes, fields, auth requirements, scopes, status codes, enum values, validation rules, WebSocket routes, OAuth behavior, and examples that imply contract changes.

## Avoid

- Do not invent routes, fields, OAuth behavior, WebSocket events, scopes, rate limits, or rules.
- Do not rewrite broad sections when a targeted edit solves the task.
- Do not change documented public behavior without backend source evidence.
- Do not commit secrets, tokens, or local environment files.
- Do not mix formatting-only rewrites with behavior changes.
