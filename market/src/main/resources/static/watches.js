import {
  $,
  esc,
  number,
  date,
  relative,
  titleCase,
  rules,
  empty,
  badge,
  metric,
  toast,
} from "./core.js";
import { openWatch } from "./watch-form.js";

export function renderWatches(root, session) {
  const { document: doc, revision } = session.config;
  root.innerHTML = `<div class="page-head"><div><div class="eyebrow">YOUR MARKET, IN FOCUS</div><h1>Market watches</h1><p>Track prices, spot opportunities, and make every alert count.</p></div><button class="primary" id="new-watch">＋ New watch</button></div>
    <div class="metrics" id="metrics"></div>
    ${!session.host.deliveryEnabled ? '<div class="notice">Notification delivery is not configured. Watches still collect signals; connect a logical ntfy topic on the host to deliver them.</div>' : ""}
    <section class="panel" aria-labelledby="watch-list-heading"><div class="panel-heading"><div class="inline-actions"><h2 id="watch-list-heading">Your watches</h2><span class="pill">${doc.watches.length + doc.contracts.length} total</span></div><small>Changes apply on save</small></div>
    <div class="toolbar"><div class="tabs" role="group" aria-label="Watch types">${[
      ["all", "All watches"],
      ["item", "Items"],
      ["riven", "Rivens"],
      ["nemesis", "Liches & sisters"],
    ]
      .map(
        ([value, label]) =>
          `<button data-filter="${value}" aria-pressed="${value === "all"}">${label}</button>`,
      )
      .join(
        "",
      )}</div><input class="search" id="watch-search" type="search" placeholder="Search watches…" aria-label="Search watches"></div>
    <div id="watch-table"></div></section>
    <section class="panel" aria-labelledby="activity-heading"><div class="panel-heading"><div><h2 id="activity-heading">Recent signals</h2><p>Your latest opportunities and delivery outcomes.</p></div><span class="pill">Latest 30</span></div><div id="activity"></div></section>`;
  let filter = "all";
  const render = () => {
    const query = $("#watch-search").value.toLowerCase();
    const entries = [
      ...doc.watches.map((watch, index) => ({ watch, index, type: "item" })),
      ...doc.contracts.map((watch, index) => ({
        watch,
        index,
        type: watch.kind,
      })),
    ];
    const rows = entries.filter(
      ({ watch, type }) =>
        (filter === "all" ||
          type === filter ||
          (filter === "nemesis" && ["lich", "sister"].includes(type))) &&
        `${watch.name} ${watch.item || watch.weapon} ${watch.rule || type}`
          .toLowerCase()
          .includes(query),
    );
    $("#watch-table").innerHTML = rows.length
      ? `<div class="table-scroll"><table><thead><tr><th>WATCH / MARKET</th><th>RULE</th><th>TRIGGER</th><th>PRIORITY</th><th>STATUS</th><th><span class="visually-hidden">Actions</span></th></tr></thead><tbody>${rows
          .map(({ watch: w, index, type }) => {
            let trigger = `${number(w.maxUnitPrice)} <span class="muted">p / unit</span>`;
            if (w.rule === "spread")
              trigger = [
                w.minSpread != null ? `${number(w.minSpread)} p` : "",
                w.minSpreadPercent != null
                  ? `${number(w.minSpreadPercent)}%`
                  : "",
              ]
                .filter(Boolean)
                .join(" + ");
            if (["volume-spike", "vanish-fast"].includes(w.rule))
              trigger = '<span class="muted">Calibrated baseline</span>';
            if (type !== "item")
              trigger =
                w.maxPrice == null
                  ? '<span class="muted">History only</span>'
                  : `${number(w.maxPrice)} <span class="muted">p buyout</span>`;
            const dimensions =
              type === "item"
                ? ["subtype", "rank", "charges", "amberStars", "cyanStars"]
                    .filter((k) => w[k] != null)
                    .map((k) => `${k === "rank" ? "R" : titleCase(k)} ${w[k]}`)
                    .join(" · ")
                : titleCase(type);
            const status = !w.enabled
              ? "paused"
              : type !== "item" && !session.host.contractsEnabled
                ? "Collector off"
                : "active";
            return `<tr><td><div class="watch-title"><span class="item-tile ${type}" aria-hidden="true">${type === "item" ? "◇" : type === "riven" ? "✧" : "⬡"}</span><div><strong>${esc(w.name)}</strong><small>${esc(titleCase(w.item || w.weapon))}${dimensions ? ` · ${esc(dimensions)}` : ""}</small></div></div></td><td>${esc(type === "item" ? rules[w.rule] || w.rule : `${titleCase(type)} contract`)}</td><td>${trigger}</td><td class="priority-${esc(String(w.priority).toLowerCase())}">${esc(titleCase(String(w.priority).toLowerCase()))}</td><td>${badge(status, status === "active" ? "● Active" : titleCase(status))}</td><td><div class="table-actions"><button data-toggle="${type}:${index}" aria-label="${w.enabled ? "Pause" : "Resume"} ${esc(w.name)}">${w.enabled ? "Pause" : "Resume"}</button><button data-edit="${type}:${index}" aria-label="Edit ${esc(w.name)}">Edit</button></div></td></tr>`;
          })
          .join(
            "",
          )}</tbody></table></div><div class="table-foot"><span>${rows.length} of ${entries.length} watches</span><span>Thresholds in platinum · priorities are per watch</span></div>`
      : empty(
          entries.length
            ? "No matching watches"
            : "Keep an eye on your first market",
          entries.length
            ? "Try a different search or watch type."
            : "Choose an item or contract, set your conditions, and let Watchdawg follow it.",
          entries.length
            ? ""
            : '<button class="primary" id="first-watch">＋ Create a watch</button>',
        );
    $("#first-watch")?.addEventListener("click", () => openWatch(session));
    root.querySelectorAll("[data-edit]").forEach(
      (button) =>
        (button.onclick = () => {
          const [type, index] = button.dataset.edit.split(":");
          openWatch(session, type, Number(index));
        }),
    );
    root.querySelectorAll("[data-toggle]").forEach(
      (button) =>
        (button.onclick = async () => {
          const [type, index] = button.dataset.toggle.split(":");
          const key = type === "item" ? "watches" : "contracts";
          const next = structuredClone(doc);
          next[key][index].enabled = !next[key][index].enabled;
          button.disabled = true;
          try {
            await session.save(next, revision);
            toast(
              next[key][index].enabled
                ? "Watch resumed."
                : "Watch paused. Pending notifications will still be delivered.",
            );
            session.render();
          } catch (error) {
            toast(error.message, true);
            button.disabled = false;
          }
        }),
    );
  };
  $("#new-watch").onclick = () => openWatch(session);
  $("#watch-search").oninput = render;
  root.querySelectorAll("[data-filter]").forEach(
    (button) =>
      (button.onclick = () => {
        filter = button.dataset.filter;
        root
          .querySelectorAll("[data-filter]")
          .forEach((b) => b.setAttribute("aria-pressed", b === button));
        render();
      }),
  );
  render();
  updateOverview(session.overview);
}

export function updateOverview(data) {
  if (!$("#metrics")) return;
  const counts = Object.fromEntries(
    data.today.map((row) => [row.state, row.count]),
  );
  const admitted =
    (counts.pending || 0) + (counts.sent || 0) + (counts.failed || 0);
  $("#metrics").innerHTML =
    metric(
      "Active watches",
      number(data.activeWatches),
      "Items and contracts",
    ) +
    metric(
      "Admitted today",
      `${number(admitted)} <small>/ ${number(data.dailyCeiling)}</small>`,
      `${number(counts.suppressed || 0)} suppressed · resets at 00:00 UTC`,
    ) +
    metric(
      "In the outbox",
      number(data.pending),
      "Pending notification delivery",
    ) +
    metric(
      "Market feed",
      `<span class="${data.socketConnected ? "green" : "accent"}">${data.socketConnected ? "● Connected" : "○ Disconnected"}</span>`,
      data.socketConnected
        ? "Receiving realtime orders"
        : "Polling runs independently",
      true,
    );
  $("#activity").innerHTML = data.signals.length
    ? data.signals
        .slice(0, 8)
        .map(
          (signal) =>
            `<div class="activity-row">${badge(signal.state)}<div class="activity-copy"><strong>${esc(signal.watch)}</strong><small class="muted"> · ${esc(rules[signal.rule] || titleCase(signal.rule))}</small></div><span class="activity-price">${number(signal.unitPrice)} <span class="muted">p</span></span><small title="${esc(date(signal.seenAt))}">${esc(relative(signal.seenAt))}</small></div>`,
        )
        .join("")
    : empty(
        "Nothing to report. Yet.",
        "Signals will appear here when a listing meets your conditions. A quiet day is fine.",
      );
}
