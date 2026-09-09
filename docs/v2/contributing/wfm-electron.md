---
sidebar_position: 5
---

# wfm-electron

[GitHub Repository](https://github.com/42bytes-team/wfm-electron)

`wfm-electron` is a desktop application example for Warframe.market integrations. Its main purpose is to demonstrate how a third-party Electron app can authenticate a user, handle an OAuth-style flow with PKCE, and call Warframe.market APIs from a desktop environment.

This repository is an integration example, not the main Warframe.market frontend.

## Current Status

OAuth 2.0 is still not publicly available for third-party client registration. That means some parts of this repository may be ahead of what public integrations can use today.

The project is still useful as a reference for desktop-app structure, local callback handling, token storage considerations, and future integration patterns.

## Good Contributions

- Fix platform-specific behavior on Windows, macOS, or Linux.
- Improve the OAuth/PKCE demo flow when public OAuth behavior changes.
- Improve error handling around authorization, token refresh, and network failures.
- Improve project structure, TypeScript types, React components, or state management.
- Improve packaging and build scripts.
- Add clearer documentation for running, building, or debugging the app.
- Fix UI bugs or accessibility issues in the demo.

## Before You Start

You should be comfortable with at least some of:

- [Electron](https://www.electronjs.org/)
- [React](https://react.dev/)
- [TypeScript](https://www.typescriptlang.org/)
- OAuth authorization-code flow with PKCE
- Desktop application packaging

For large changes, open an issue or discussion first. This is especially important for authentication behavior, token storage, or anything that changes the security model.

## Running Locally

Install dependencies:

```bash
yarn install
```

Start the app:

```bash
yarn start
```

Build/package the app:

```bash
yarn make && yarn package
```

The project uses Electron Forge for building and packaging.

## Security Expectations

Authentication examples should be treated carefully.

When touching auth-related code:

- Do not log access tokens, refresh tokens, authorization codes, or secrets.
- Do not store tokens in plain text unless the repository already does so for demo-only reasons and the documentation makes that clear.
- Do not weaken PKCE or redirect validation.
- Do not add hardcoded production credentials.
- Prefer simple, explicit flows over clever abstractions.

## Pull Request Expectations

A good pull request explains:

- What platform you tested on.
- What command you ran.
- Whether the change affects authentication behavior.
- Screenshots or screen recordings for UI changes.
- Any limitations you noticed.

Cross-platform fixes are especially welcome because the original demo was not deeply tested across every OS.

## Avoid

- Do not present this repository as the official Warframe.market desktop client.
- Do not commit credentials, tokens, or local environment files.
- Do not make major OAuth behavior changes without checking current public OAuth availability.
- Do not add heavy dependencies without a clear reason.
- Do not mix formatting-only rewrites with behavior changes.
