---
sidebar_position: 1
---

# Rules

These rules apply to clients, integrations, bots, scripts, and services that interact with Warframe.market APIs, WebSockets, or public data.

Warframe.market is a community project with limited infrastructure. We welcome useful third-party tools, but clients must be identifiable, respectful of users, and careful with server resources.

## Identify Your Application

Every application should use a dedicated and descriptive `User-Agent` header.

A good `User-Agent` identifies your project and gives us a way to understand where traffic comes from. If possible, include a project name, version, website, repository, or contact address.

Example:

```http
User-Agent: ExampleMarketTool/1.2.0 (+https://example.com/contact)
```

Do not disguise your application as a regular browser or another application. Undefined browser-like automated traffic is expensive to investigate and easy to confuse with malicious traffic, botnets, scrapers, or abusive automated parsers.

Applications that hide their identity, impersonate browsers, or intentionally make traffic difficult to classify may be blocked.

## Do Not Clone Warframe.market

Do not build an application that simply replicates the Warframe.market website while using our APIs and infrastructure as the backend.

Third-party tools should add meaningful value, such as specialized workflows, accessibility improvements, notifications, analytics, overlays, or integrations. A direct copy of the website shifts traffic and infrastructure cost to us while duplicating the user experience and competing for the same user activity.

We may restrict applications that are primarily website clones, data mirrors, or traffic offloading layers.

## Be Careful With Requests

Design your application to minimize unnecessary API calls.

Use caching, reuse responses, avoid tight polling loops, and prefer incremental updates or WebSocket subscriptions when appropriate. Do not repeatedly fetch large collections or high-traffic endpoints when local caching would work.

Warframe.market does not have unlimited infrastructure budget. Inefficient clients can degrade service quality for everyone.

## Rate Limits

The general public API limit is `3` requests per second.

Some endpoints may have stricter limits. Contract search endpoints are expected to be limited to roughly `10` to `20` requests per minute.

Rate limits may change without notice when needed to protect service stability.

## Access May Be Restricted

We reserve the right to temporarily or permanently restrict access to Warframe.market services, APIs, WebSockets, or specific endpoints.

Restrictions may apply to individual clients, tokens, users, IP addresses, networks, cloud providers, countries, regions, or traffic patterns. We may do this without prior notice when traffic creates operational risk, affects users, resembles abuse, or becomes too costly to manage.

Sometimes protective measures are broad. During DDoS attacks, scraping waves, abusive automation, or Cloudflare-level mitigation, legitimate applications may be affected. We cannot guarantee exceptions or uninterrupted API access during those events.

## Automation And Trade Bots

Automation and trade bots are currently a grey area.

They can provide significant advantages to some users and may affect market behavior, user experience, and infrastructure load. We expect stricter limits and restrictions for automation and trade bots in the future.

If your application automates trading behavior, assume that rules may change and that your access may be limited later.

## Questions

For third-party developer questions, use our Discord server:

[Join the Warframe.market Discord](https://discord.gg/M7BHnPS)
