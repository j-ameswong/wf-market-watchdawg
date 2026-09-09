---
sidebar_position: 3
---

# wfm-localization

[GitHub Repository](https://github.com/42bytes-team/wfm-localization)

`wfm-localization` contains the interface translations used by Warframe.market. If you want to improve the wording users see in menus, buttons, forms, warnings, filters, settings, and other UI text, this is the repository to edit.

This repository is for application text. Item names, item descriptions, Riven attributes, Lich entities, and other game-data translations usually belong in [wfm-items](./wfm-items.md) instead.

## Good Contributions

- Add a missing language by creating translation files from the English source.
- Fill missing keys in an existing language.
- Fix mistranslations, awkward wording, typos, or inconsistent terminology.
- Improve marketplace-specific terminology so it matches how Warframe players actually talk in that language.
- Translate miscellaneous UI strings such as rarity labels and generated text templates.
- Keep existing translations up to date when English strings change.

Small, focused pull requests are easier to review than very large rewrites. If you are changing many translations at once, group them by language or topic.

## Repository Layout

The exact layout may change, but the important areas are:

| Area        | Purpose                                                                      |
| ----------- | ---------------------------------------------------------------------------- |
| `locales/`  | Main UI translation files.                                                   |
| `misc/`     | Additional translation files for labels, templates, and generated UI text.   |
| `en.json`   | English source text and the best template for new languages.                 |
| `overwolf/` | Legacy/special integration files. Do not edit unless maintainers ask you to. |

## Translation Rules

Only translate values. Do not change keys.

```json
{
  "settings.language": "Language"
}
```

In this example, translate `Language`, but do not change `settings.language`.

Placeholders in curly braces usually must stay unchanged:

```text
{itemName}
{count}
{platform}
```

If a string uses ICU `select` syntax, keep the variable and option names intact, but translate the user-visible text inside option blocks.

```text
{platform, select, pc {} xbox {| Xbox } other {}}
```

In that example, translate text such as `Xbox` only if the language requires it. Do not rename `platform`, `pc`, `xbox`, or `other`.

## How To Contribute With Git

1. Fork the repository.
2. Create a branch for your language or topic.
3. Edit the relevant files in `locales/` or `misc/`.
4. Keep JSON valid.
5. Submit a pull request with a short explanation of what you changed.

Useful pull request descriptions mention:

- Language code.
- Whether this is a new translation or an update.
- Whether you are a native/fluent speaker or using another source.
- Any terms that may need maintainer review.

## How To Contribute Without Git

If you are not comfortable with GitHub:

1. Open the repository on GitHub.
2. Download the repository as a ZIP file.
3. Edit or create translation files locally.
4. Send the changed files to maintainers through the [Warframe.market Discord](https://discord.gg/M7BHnPS).

This is slower than a pull request, but translation contributions are still welcome.

## Review Expectations

Maintainers mostly check that:

- JSON files are valid.
- Keys were not renamed or deleted accidentally.
- Placeholders were preserved.
- The contribution belongs in `wfm-localization` rather than `wfm-items`.
- The translation does not include jokes, machine-generated nonsense, or unrelated rewrites.

We may not be able to verify every language ourselves. Community review from native or fluent speakers is very helpful.

## Avoid

- Do not translate JSON keys.
- Do not remove placeholders like `{itemName}`.
- Do not edit `overwolf/` files unless requested.
- Do not move item or game-data translations into this repository.
- Do not submit unreviewed machine translation dumps as final translations.
- Do not mix many unrelated languages in one large pull request unless there is a good reason.
