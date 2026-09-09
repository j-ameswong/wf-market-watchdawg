---
sidebar_position: 4
---

# wfm-items

[GitHub Repository](https://github.com/42bytes-team/wfm-items)

`wfm-items` is the public data repository for Warframe.market item and game-data metadata. It helps power item pages, search, filters, order forms, images, translations, set relationships, Riven data, Lich and Sister entities, and other marketplace-facing game data.

If something is wrong with an item page, missing from the market, mistranslated, using a bad icon, or incorrectly grouped into a set, this is usually the repository to update.

## Good Contributions

- Add missing tradable items.
- Fix item names, descriptions, wiki links, icons, or thumbnails.
- Correct whether an item is tradable, bulk tradable, vaulted, ranked, charged, or part of a set.
- Fix set relationships and set roots.
- Improve item translations.
- Add or update drop/location information when that data is maintained in the repository.
- Translate Riven attributes, Lich quirks, Lich ephemeras, Sister quirks, and Sister ephemeras.
- Replace broken or low-quality icons with better assets.

## Repository Layout

The repository is organized around how data is maintained.

| Area              | Purpose                                                       |
| ----------------- | ------------------------------------------------------------- |
| `tracked/`        | Items that are synced or compared with upstream game data.    |
| `untracked/`      | Items that need manual maintenance.                           |
| `missing/`        | New or missing items that should be added to Warframe.market. |
| `icons/`          | Local icon assets used by item JSON files.                    |
| `tracked/rivens/` | Riven weapons and attributes.                                 |

Folder names and generation scripts may evolve, so follow the structure already used by nearby files.

## Adding A Missing Item

1. Check whether the item already exists in `tracked/` or `untracked/`.
2. If it is missing, create a new JSON file in `missing/`.
3. Use a similar existing item as a template.
4. Fill the English data first.
5. Add translations only when you are confident they are correct.
6. Add or reference an icon if available.
7. Submit a pull request explaining where the item appears in game and why it should be tradable.

Good evidence includes a Warframe Wiki page, in-game screenshots, official patch notes, or a similar already-listed item.

## Editing Existing Items

When updating existing data, keep the change focused.

For example:

- One pull request for a broken set relationship.
- One pull request for a group of missing translations in one language.
- One pull request for new icons for related items.

Avoid mixing unrelated fixes unless they are very small.

## Item JSON Notes

Item files commonly include language-specific blocks such as `en`, `ru`, or `ko` for display text and links.

Common fields may include:

- `tradable`
- `part_of_set`
- `set_root`
- `rarity`
- `max_rank`
- `mastery_level`
- `ducats`
- icon or image paths

Not every item uses every field. Copy the pattern from similar items instead of adding fields blindly.

## Sets

Set relationships are important because they affect item pages and marketplace search.

When editing sets:

- Mark the root item with `set_root` when the data format requires it.
- Make sure every part references the same set members.
- Use existing `url_name` values exactly.
- Check that the set root and set parts match what users expect to trade.

## Icons

Icons should be clear, correctly cropped, and match the item they represent.

Depending on the existing file pattern, icons may be referenced by URL or stored in the repository. Prefer matching the style used by nearby item files.

## Review Expectations

Maintainers usually check:

- The item is actually tradable or relevant to Warframe.market.
- JSON is valid.
- Field names and structure match existing files.
- Set relationships are correct.
- Icons are acceptable.
- Translations do not modify unrelated languages.
- Riven/Lich/Sister data is in the correct folder.

Changes are not always reflected on the live site immediately. Data imports and deployment can take time.

## Avoid

- Do not change `_id` fields unless maintainers specifically request it.
- Do not invent item data without evidence.
- Do not add non-tradable items unless there is a clear marketplace reason.
- Do not submit large machine-translated batches without review.
- Do not change generated or tracked data in ways that will be overwritten unless you understand the import flow.
- Do not mix item data changes with unrelated repository cleanup.
