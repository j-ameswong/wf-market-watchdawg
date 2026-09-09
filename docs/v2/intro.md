---
sidebar_position: 1
---

# Introduction

Welcome to the Warframe.market developer documentation.

These docs describe the public contracts for Warframe.market HTTP APIs, realtime WebSocket messages, shared data models, OAuth plans, and public API-client rules.

## Current State

The current API and WebSocket contracts are still below `1.0`. Expect changes, including breaking changes, while the new public API is being rolled out.

New API endpoints are added gradually. Some areas are already documented, while others may still be missing or incomplete.

The legacy v1 API is deprecated and unsupported. We do not plan to publish new v1 documentation.

## OAuth 2.0 Status

OAuth 2.0 is still in development and is not available to public integrations yet.

It is not currently possible to register your own OAuth client. For now, integrations that require user authorization still need to rely on the existing v1 authorization flow.

We know this is not ideal, and we are sorry for the messy transition period.

## Where To Start

| Area                                    | Use It For                                                                                                     |
| --------------------------------------- | -------------------------------------------------------------------------------------------------------------- |
| [HTTP API](./api/overview.mdx)          | Reading marketplace data, managing orders, working with users, achievements, manifests, and dashboard content. |
| [WebSockets](./websockets/overview.mdx) | Receiving realtime status updates, online reports, account events, and order subscription events.              |
| [Data Models](./data-models.mdx)        | Understanding reusable response shapes such as `Item`, `Order`, `User`, `RichStatus`, and shared enum values.  |
| [OAuth 2.0](./oauth/overview.md)        | Planned OAuth 2.0 behavior. Public client registration is not available yet.                                   |
| [Rules](./rules/overview.md)            | Understanding public marketplace and API-client policy.                                                        |

## Questions And Support

Third-party developer questions should be directed to our Discord server:

[Join the Warframe.market Discord](https://discord.gg/M7BHnPS)

## Contributing

We welcome improvements to this documentation project on GitHub.

If you want to improve wording, fix mistakes, or add missing documentation, see [Documentation Style](./contributing/documentation-style.md).
