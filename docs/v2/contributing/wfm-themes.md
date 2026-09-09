---
sidebar_position: 6
---

# wfm-themes

[GitHub Repository](https://github.com/42bytes-team/wfm-themes)

`wfm-themes` contains custom visual themes for Warframe.market. Themes define color palettes and SCSS variables that can change the appearance of the website for users who want a different visual style.

This repository is best for contributors who enjoy visual design, accessibility, contrast tuning, and CSS/SCSS work.

## Good Contributions

- Create a complete new theme.
- Improve contrast or readability in an existing theme.
- Fix missing variables, broken colors, or compilation issues.
- Adjust colors so UI states are easier to distinguish.
- Make a theme more consistent across buttons, inputs, tables, cards, navigation, and backgrounds.
- Improve documentation for building or testing themes.

## Theme Quality Goals

A good theme should be more than a random palette swap.

Check that the theme handles:

- Page background and surface colors.
- Text contrast for primary and secondary text.
- Links and hover states.
- Buttons, inputs, dropdowns, and focus states.
- Tables, cards, modals, and sidebars.
- Success, warning, danger, online, offline, buy, and sell indicators.
- Code or data-heavy pages where readability matters.

Themes should be visually distinct, but still usable for marketplace workflows.

## Requirements

You need Node.js and Sass tooling.

If the repository does not install Sass locally, install it globally:

```bash
yarn global add sass
```

## Creating A Theme

1. Fork the repository.
2. Create a new folder under `themes/`.
3. Use an existing theme as the structure template.
4. Define your palette and variables in `_main.scss`.
5. Compile the theme.
6. Test it on real Warframe.market pages.
7. Submit a pull request with screenshots.

## Compiling

On Linux, the repository build script may be available:

```bash
./build.sh
```

You can also compile a single theme with Sass:

```bash
sass --no-source-map "./themes/{THEME_FOLDER}/_main.scss" compiled/{THEME_NAME}.css
```

Add `--watch` while iterating:

```bash
sass --watch --no-source-map "./themes/{THEME_FOLDER}/_main.scss" compiled/{THEME_NAME}.css
```

## Testing

Use a browser extension that can inject custom CSS, then load your compiled theme on Warframe.market.

Test more than one page. At minimum, check:

- Home or landing pages.
- Item pages.
- Order lists.
- Forms and filters.
- User profile pages.
- Light/dark-sensitive UI states if relevant.

Screenshots are very helpful in pull requests.

## Accessibility

Please pay attention to contrast.

Avoid themes where:

- Text blends into the background.
- Links are hard to distinguish from normal text.
- Buy/sell states are too similar.
- Error and success colors are unclear.
- Focus states disappear.

You do not need to create a perfect accessibility audit, but the theme should remain comfortable to use for real marketplace browsing.

## Pull Request Expectations

A good pull request includes:

- Theme name.
- Short design intent or inspiration.
- Screenshots of several pages.
- Notes about any known weak areas.
- Confirmation that the theme compiles.

## Avoid

- Do not submit themes that only change one or two colors.
- Do not make text low contrast for aesthetics.
- Do not break important UI states such as warnings, errors, online status, buy, or sell.
- Do not edit unrelated themes in the same pull request unless fixing shared structure.
- Do not submit generated CSS only without the source SCSS changes.
