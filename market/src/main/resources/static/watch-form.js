import {
  $,
  api,
  esc,
  options,
  rules,
  titleCase,
  toast,
  labelControls,
} from "./core.js";

let editorGeneration = 0;

export async function openWatch(session, initialType = "item", index = null) {
  const generation = ++editorGeneration;
  const active = () => generation === editorGeneration;
  const dialog = $("#watch-dialog");
  const baseRevision = session.config.revision;
  const originalDoc = structuredClone(session.config.document);
  const existing =
    index == null
      ? null
      : originalDoc[initialType === "item" ? "watches" : "contracts"][index];
  let type = initialType,
    metadata = null,
    selectedItem = "",
    lookupVersion = 0,
    dirty = false;
  let catalog;
  try {
    catalog = await api("contract-catalog");
    if (!active() || session.page !== "watches") return;
  } catch (error) {
    toast(error.message, true);
    return;
  }
  const initial = existing || {
    name: "",
    item: "",
    weapon: "",
    rule: "underpriced",
    maxUnitPrice: "",
    maxPrice: null,
    priority: "DEFAULT",
    cooldown: "PT1H",
    topic: "default",
    enabled: true,
  };
  const topics = [
    ...new Set(["default", ...session.host.topics, initial.topic]),
  ];
  dialog.innerHTML = `<form id="watch-form"><div class="dialog-head"><div><h2 id="dialog-title">${existing ? "Edit watch" : "New watch"}</h2><small>Define what deserves your attention.</small></div><button type="button" class="icon-button" data-close aria-label="Close watch editor">×</button></div>
    <div class="form-body"><div id="form-error" class="form-error" role="alert" hidden></div><div class="form-grid">
    <label>Watch type<select name="type" ${existing ? "disabled" : ""}>${options(
      [
        ["item", "Item market"],
        ["riven", "Riven"],
        ["lich", "Kuva lich"],
        ["sister", "Sister of Parvos"],
      ],
      type,
    )}</select></label>
    <label>Watch name<input name="name" value="${esc(initial.name)}" required maxlength="80" pattern="[a-zA-Z0-9][a-zA-Z0-9_\\-]*" placeholder="e.g. serration-max" ${existing ? "readonly" : ""}><small>${existing ? "A stable name keeps its alert history." : "Letters, numbers, hyphens and underscores."}</small></label>
    <div id="target-fields" class="span-2"></div><div id="rule-fields" class="span-2"></div></div>
    <div class="form-section">Delivery preferences</div><div class="form-grid">
    <label>Priority<select name="priority">${options(
      ["MIN", "LOW", "DEFAULT", "HIGH", "MAX"].map((x) => [
        x,
        titleCase(x.toLowerCase()),
      ]),
      initial.priority,
    )}</select><small>Also orders contract collection.</small></label>
    <label>Cooldown<input name="cooldown" value="${esc(shortDuration(initial.cooldown))}" required placeholder="1h"><small>Examples: 30s, 15m, 1h, 1d. Zero disables cooldown.</small></label>
    <label>Notification topic<select name="topic">${options(topics, initial.topic)}</select><small>Logical names only. Credentials stay on the host.</small></label>
    <label class="check-label"><input name="enabled" type="checkbox" ${initial.enabled ? "checked" : ""}> Watch enabled</label></div></div>
    <div class="dialog-actions">${existing ? '<button type="button" class="danger left" id="delete-watch">Delete watch</button>' : ""}<button type="button" data-close>Cancel</button><button type="submit" class="primary" id="save-watch">${existing ? "Save changes" : "Create watch"}</button></div></form>`;
  const form = $("#watch-form");
  const close = () => {
    if (!dirty || window.confirm("Discard unsaved watch changes?")) {
      ++editorGeneration;
      dialog.close();
      session.dirty = false;
    }
  };
  dialog.querySelectorAll("[data-close]").forEach((b) => (b.onclick = close));
  dialog.oncancel = (event) => {
    event.preventDefault();
    close();
  };
  dialog.onclose = () => {
    if (active() && !dialog.open) ++editorGeneration;
  };
  form.oninput = () => {
    dirty = true;
    session.dirty = true;
  };
  form.onchange = () => {
    dirty = true;
    session.dirty = true;
  };
  function dimensions(item, values) {
    const fields = [];
    if (item.subtypes?.length)
      fields.push([
        "subtype",
        "Subtype",
        [
          ["any", "Any subtype"],
          ...item.subtypes.map((x) => [x, titleCase(x)]),
        ],
      ]);
    for (const [key, label, max] of [
      ["rank", "Rank", item.maxRank],
      ["charges", "Charges", item.maxCharges],
      ["amberStars", "Amber stars", item.maxAmberStars],
      ["cyanStars", "Cyan stars", item.maxCyanStars],
    ]) {
      if (max != null)
        fields.push([
          key,
          label,
          [
            ["any", `Any ${label.toLowerCase()}`],
            ...Array.from({ length: max + 1 }, (_, n) => [
              String(n),
              String(n),
            ]),
          ],
        ]);
    }
    return `<div class="form-grid">${fields.map(([key, label, choices]) => `<label>${label}<select name="${key}">${options(choices, values[key] ?? "any")}</select></label>`).join("")}</div>${!item.detailSyncedAt ? '<p class="field-note">Item details have not been collected yet. Charge limits may become available after the next collection.</p>' : ""}`;
  }
  async function selectItem(slug, values = {}) {
    const version = ++lookupVersion;
    metadata = null;
    selectedItem = "";
    $("#dimensions").innerHTML =
      '<p class="field-note">Looking up item dimensions…</p>';
    try {
      const items = await api(`items?q=${encodeURIComponent(slug)}`);
      if (
        !active() ||
        !dialog.open ||
        version !== lookupVersion ||
        type !== "item"
      )
        return;
      $("#item-options").innerHTML = items
        .map((i) => `<option value="${esc(i.slug)}">${esc(i.name)}</option>`)
        .join("");
      const match = items.find((i) => i.slug === slug);
      if (!match) {
        $("#dimensions").innerHTML =
          '<p class="field-note">Select an exact item slug from the catalog suggestions.</p>';
        return;
      }
      metadata = match;
      selectedItem = slug;
      $("#dimensions").innerHTML = dimensions(match, values);
      labelControls(dialog);
      if (!form.elements.name.value)
        form.elements.name.value = slug.replaceAll("_", "-");
    } catch (error) {
      if (active() && dialog.open && version === lookupVersion)
        $("#dimensions").innerHTML =
          `<p class="form-error">${esc(error.message)}</p>`;
    }
  }
  function itemRule(values) {
    const rule = form.elements.rule.value;
    let fields = "";
    if (["underpriced", "price-crossing"].includes(rule))
      fields = `${
        rule === "price-crossing"
          ? `<label>Order side<select name="side">${options(
              [
                ["sell", "Sell price at or below"],
                ["buy", "Buy price at or above"],
              ],
              values.side || "sell",
            )}</select></label>`
          : ""
      }<label>${rule === "underpriced" ? "Maximum unit price" : "Price threshold"} (p)<input name="maxUnitPrice" type="number" min="0.01" step="0.01" required value="${esc(values.maxUnitPrice || "")}" placeholder="18.00"></label>`;
    if (rule === "spread")
      fields = `<label>Minimum spread (p)<input name="minSpread" type="number" min="0.01" step="0.01" value="${esc(values.minSpread ?? "")}" placeholder="Optional"></label><label>Minimum spread (%)<input name="minSpreadPercent" type="number" min="0.01" step="0.01" value="${esc(values.minSpreadPercent ?? "")}" placeholder="Optional"></label><p class="field-note span-2">Set at least one threshold. When both are set, both must pass.</p>`;
    if (["volume-spike", "vanish-fast"].includes(rule))
      fields = `<div class="notice span-2">This rule learns from observed history. Alerts stay in learning mode until calibration and shared budget evidence qualify.${rule === "vanish-fast" ? " Disappearance is not proof of a sale." : ""}</div><label>Minimum samples<input name="minSamples" type="number" min="10" max="100000" required value="${esc(values.minSamples ?? 35)}"></label><label>Target candidate rate (%)<input name="targetRate" type="number" min="0.01" max="99.99" step="0.01" required value="${esc(Number(values.targetRate ?? 0.1) * 100)}"></label>`;
    $("#threshold-fields").innerHTML = `<div class="form-grid">${fields}</div>`;
    labelControls(dialog);
  }
  function addAttribute(name = "", min = "") {
    const row = document.createElement("div");
    row.className = "attribute-row";
    row.innerHTML = `<select aria-label="Positive attribute">${options([["", "Select attribute"], ...catalog.attributes.map((a) => [a.slug, `${titleCase(a.slug)}${a.positiveIsNegative ? " (benefit magnitude)" : ""}`])], name)}</select><input aria-label="Minimum benefit" type="number" min="0" step="any" placeholder="Min." value="${esc(min)}"><button type="button" aria-label="Remove positive attribute">×</button>`;
    $("button", row).onclick = () => {
      row.remove();
      dirty = true;
      session.dirty = true;
    };
    $("#positive-attributes").append(row);
  }
  function targetFields(values = {}) {
    lookupVersion++;
    metadata = null;
    selectedItem = "";
    if (type === "item") {
      $("#target-fields").innerHTML =
        `<label>Item<input name="item" aria-label="Item" list="item-options" autocomplete="off" required placeholder="Search by item name or slug" value="${esc(values.item || "")}"><datalist id="item-options"></datalist></label><div id="dimensions" class="field-note">Choose an item to select its market dimensions.</div>`;
      $("#rule-fields").innerHTML =
        `<div class="form-section">Alert condition</div><label>Rule<select name="rule">${options(Object.entries(rules), values.rule || "underpriced")}</select></label><div id="threshold-fields" class="form-section"></div>`;
      form.elements.rule.onchange = () => itemRule({});
      itemRule(values);
      let timer;
      form.elements.namedItem("item").oninput = () => {
        metadata = null;
        clearTimeout(timer);
        timer = setTimeout(
          () => selectItem(form.elements.namedItem("item").value.trim()),
          220,
        );
      };
      form.elements.namedItem("item").onchange = () => {
        clearTimeout(timer);
        selectItem(
          form.elements.namedItem("item").value.trim(),
          form.elements.namedItem("item").value.trim() === values.item
            ? values
            : {},
        );
      };
      if (values.item) selectItem(values.item, values);
      else
        api("items")
          .then((items) => {
            if (active() && dialog.open && type === "item")
              $("#item-options").innerHTML = items
                .map(
                  (i) =>
                    `<option value="${esc(i.slug)}">${esc(i.name)}</option>`,
                )
                .join("");
          })
          .catch((error) => toast(error.message, true));
    } else {
      $("#target-fields").innerHTML =
        `<label>Weapon<select name="weapon" required>${options([["", "Select weapon"], ...catalog.weapons.filter((w) => w.kind === type).map((w) => [w.slug, w.name])], values.weapon || "")}</select></label>${!session.host.contractsEnabled ? '<p class="field-note">Contract collection is disabled on this host. Enable it in host configuration before this watch can collect.</p>' : ""}`;
      form.elements.weapon.onchange = () => {
        if (!form.elements.name.value)
          form.elements.name.value = `${type}-${form.elements.weapon.value.replaceAll("_", "-")}`;
      };
      const common = `<div class="form-section">Contract conditions</div><label>Maximum buyout (p)<input name="maxPrice" type="number" min="1" step="1" value="${esc(values.maxPrice ?? "")}" placeholder="No alert cap — collect history only"><small>Leave blank to collect history without buyout alerts.</small></label>`;
      if (type === "riven") {
        $("#rule-fields").innerHTML =
          `${common}<div class="form-section">Riven attributes</div><div class="attribute-tools"><span class="small-heading">Required positive attributes</span><button id="add-attribute" type="button">＋ Add attribute</button></div><div id="positive-attributes"></div><p class="field-note">Minimum beneficial value; inverted attributes such as recoil use a positive magnitude.</p><div class="form-grid form-section"><label>Required negative attributes<select name="negative" multiple size="4">${catalog.attributes.map((a) => `<option value="${esc(a.slug)}" ${(values.negative || []).includes(a.slug) ? "selected" : ""}>${esc(titleCase(a.slug))}</option>`).join("")}</select><small>Ctrl / Cmd to select more than one.</small></label><div><label>Maximum rerolls<input name="maxRerolls" type="number" min="0" step="1" value="${esc(values.maxRerolls ?? "")}" placeholder="Any"></label><label class="check-label form-section"><input name="noNegative" type="checkbox" ${values.noNegative ? "checked" : ""}> No negative attributes</label></div></div>`;
        $("#add-attribute").onclick = () => addAttribute();
        Object.entries(values.positive || {}).forEach(([name, min]) =>
          addAttribute(name, min),
        );
        const negativeState = () => {
          form.elements.negative.disabled = form.elements.noNegative.checked;
        };
        form.elements.noNegative.onchange = negativeState;
        negativeState();
      } else {
        $("#rule-fields").innerHTML =
          `${common}<div class="form-grid form-section"><label>Element<select name="element">${options([["", "Any element"], ...["impact", "heat", "cold", "electricity", "toxin", "magnetic", "radiation"]], values.element || "")}</select></label><label>Minimum damage (%)<input name="minDamage" type="number" min="25" max="60" step="1" value="${esc(values.minDamage ?? "")}" placeholder="Any"></label><label>Ephemera<select name="ephemera">${options(
            [
              ["", "Any"],
              ["true", "Required"],
              ["false", "Without ephemera"],
            ],
            values.ephemera ?? "",
          )}</select></label></div>`;
      }
    }
  }
  form.elements.type.onchange = () => {
    type = form.elements.type.value;
    targetFields();
    labelControls(dialog);
  };
  targetFields(initial);
  labelControls(dialog);
  form.onsubmit = async (event) => {
    event.preventDefault();
    const errorBox = $("#form-error");
    errorBox.hidden = true;
    const field = (name) => form.elements.namedItem(name)?.value;
    const optionalNumber = (name) =>
      field(name) === "" || field(name) == null ? null : Number(field(name));
    const submit = $("#save-watch");
    submit.disabled = true;
    try {
      const watch = {
        name: field("name").trim(),
        enabled: form.elements.enabled.checked,
        priority: field("priority"),
        cooldown: parseDuration(field("cooldown")),
        topic: field("topic"),
      };
      if (type === "item") {
        if (!metadata || selectedItem !== field("item").trim())
          throw new Error(
            "Select an exact item from the catalog and wait for its dimensions to load.",
          );
        Object.assign(watch, {
          item: selectedItem,
          rule: field("rule"),
          side: field("side") || "sell",
          maxUnitPrice: optionalNumber("maxUnitPrice") ?? 0,
          minSpread: optionalNumber("minSpread"),
          minSpreadPercent: optionalNumber("minSpreadPercent"),
          minSamples: optionalNumber("minSamples") ?? 35,
          targetRate: (optionalNumber("targetRate") ?? 10) / 100,
        });
        for (const key of [
          "subtype",
          "rank",
          "charges",
          "amberStars",
          "cyanStars",
        ])
          watch[key] = field(key) ?? null;
      } else {
        Object.assign(watch, {
          kind: type,
          weapon: field("weapon"),
          maxPrice: optionalNumber("maxPrice"),
        });
        if (type === "riven") {
          watch.positive = {};
          for (const row of dialog.querySelectorAll(".attribute-row")) {
            const name = $("select", row).value,
              value = $("input", row).value;
            if (!name || value === "")
              throw new Error(
                "Choose each positive attribute and its minimum benefit, or remove the row.",
              );
            if (name in watch.positive)
              throw new Error("Each positive attribute can appear only once.");
            watch.positive[name] = Number(value);
          }
          watch.noNegative = form.elements.noNegative.checked;
          watch.negative = watch.noNegative
            ? []
            : [...form.elements.negative.selectedOptions].map((o) => o.value);
          watch.maxRerolls = optionalNumber("maxRerolls");
        } else
          Object.assign(watch, {
            element: field("element") || null,
            minDamage: optionalNumber("minDamage"),
            ephemera:
              field("ephemera") === "" ? null : field("ephemera") === "true",
          });
      }
      const key = type === "item" ? "watches" : "contracts";
      const next = structuredClone(originalDoc);
      if (index == null) next[key].push(watch);
      else next[key][index] = watch;
      await session.save(next, baseRevision);
      if (!active()) {
        toast("Watch change saved.");
        return;
      }
      dirty = false;
      session.dirty = false;
      ++editorGeneration;
      dialog.close();
      toast(existing ? "Watch updated." : "Watch created.");
      session.render();
    } catch (error) {
      if (!active()) return;
      errorBox.textContent = error.message;
      errorBox.hidden = false;
      errorBox.scrollIntoView({ block: "nearest" });
    } finally {
      submit.disabled = false;
    }
  };
  $("#delete-watch")?.addEventListener("click", async () => {
    if (
      !window.confirm(
        `Delete “${existing.name}”? Its collected history and pending notifications will be kept.`,
      )
    )
      return;
    const next = structuredClone(originalDoc);
    next[type === "item" ? "watches" : "contracts"].splice(index, 1);
    try {
      await session.save(next, baseRevision);
      if (!active()) {
        toast("Watch change saved.");
        return;
      }
      dirty = false;
      session.dirty = false;
      ++editorGeneration;
      dialog.close();
      toast("Watch deleted. History kept.");
      session.render();
    } catch (error) {
      if (!active()) return;
      $("#form-error").textContent = error.message;
      $("#form-error").hidden = false;
    }
  });
  dialog.showModal();
}

function shortDuration(value) {
  const match = String(value).match(/^PT(\d+)([HMS])$/);
  return match ? `${match[1]}${match[2].toLowerCase()}` : value;
}
function parseDuration(value) {
  if (/^P/i.test(value)) return value.toUpperCase();
  const match = value.trim().match(/^(\d+(?:\.\d+)?)\s*(s|m|h|d)?$/i);
  if (!match) throw new Error("Use a cooldown like 30s, 15m, 1h or 1d.");
  return `PT${Number(match[1]) * { s: 1, m: 60, h: 3600, d: 86400 }[match[2]?.toLowerCase() || "s"]}S`;
}
