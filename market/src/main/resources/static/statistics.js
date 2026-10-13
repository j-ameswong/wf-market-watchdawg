import {
  $,
  api,
  esc,
  number,
  date,
  relative,
  titleCase,
  options,
  empty,
  badge,
  marketLabel,
  showError,
  labelControls,
} from "./core.js";
import { renderChart } from "./chart.js";

export async function renderStatistics(root, session) {
  const epoch = session.renderEpoch,
    valid = () => session.renderEpoch === epoch;
  const prefs = session.stats;
  root.innerHTML = `<div class="page-head"><div><div class="eyebrow">A CLEARER VIEW OF THE MARKET</div><h1>Statistics</h1><p>Explore observed prices and volume, one market at a time.</p></div><span class="pill">Stored observations</span></div>
    <div class="tabs" role="group" aria-label="Statistics view"><button id="item-mode" aria-pressed="${prefs.mode !== "contracts"}">Item history</button><button id="contract-mode" aria-pressed="${prefs.mode === "contracts"}">Contract listings</button></div><div id="statistics-content" class="statistics-content"></div>`;
  $("#item-mode").onclick = () => {
    prefs.mode = "items";
    session.render();
  };
  $("#contract-mode").onclick = () => {
    prefs.mode = "contracts";
    session.render();
  };
  const panel = $("#statistics-content");
  if (prefs.mode === "contracts") {
    await renderContracts(panel, session, valid);
    return;
  }
  if (!prefs.item) prefs.item = session.config.document.watches[0]?.item || "";
  panel.innerHTML = `<section class="panel"><div class="filters-grid"><label>Item<input id="stat-item" aria-label="Item" list="stat-item-options" value="${esc(prefs.item)}" placeholder="Search the item catalog" autocomplete="off"><datalist id="stat-item-options"></datalist></label><label>Market<select id="stat-market"><option value="">Select an item first</option></select></label><label>Population<select id="stat-crossplay">${options(
    [
      ["true", "Crossplay"],
      ["false", "Platform only"],
    ],
    prefs.crossplay ?? String(session.host.crossplay),
  )}</select></label></div>
    <div class="chart-filters"><label>Series<select id="stat-series">${options(
      [
        ["closed", "Closed trades"],
        ["sell", "Live sell orders"],
        ["buy", "Live buy orders"],
      ],
      prefs.series || "closed",
    )}</select></label><label>Resolution<select id="stat-resolution">${options(
      [
        ["daily", "Daily"],
        ["hourly", "Hourly"],
      ],
      prefs.resolution || "daily",
    )}</select></label><div class="tabs" role="group" aria-label="Date range">${[7, 30, 90].map((d) => `<button data-days="${d}" aria-pressed="${d === (prefs.days || 30)}">${d}D</button>`).join("")}</div></div></section>
    <section class="panel"><div class="panel-heading"><div><h2 id="chart-title">Price & volume</h2><p id="chart-subtitle">Choose an exact market to explore.</p></div><span class="pill" id="stats-freshness">No selection</span></div><div id="stat-summary"></div><div id="chart-content">${empty("Choose a market", "Search for an item above, then choose its rank and other market dimensions.")}</div><div class="spaced-note">Statistics are snapshots reported by warframe.market. Missing buckets stay empty; live listings and reported closed trades are separate series.</div></section>
    <section class="panel"><div class="panel-heading"><div><h2>Baseline readiness</h2><p>Calibration for this market and crossplay population.</p></div></div><div id="baseline-content">${empty("No market selected", "Select a market to inspect its calibration evidence.")}</div></section>`;
  const clearEvidence = () => {
    $("#stat-summary").innerHTML = "";
    $("#chart-title").textContent = "Price & volume";
    $("#chart-subtitle").textContent = "Loading selected market…";
    $("#stats-freshness").textContent = "Loading…";
    $("#stats-freshness").title = "";
    $("#baseline-content").innerHTML = empty(
      "Evidence not loaded",
      "Waiting for the selected market and population.",
    );
    $("#chart-content").innerHTML =
      '<div class="loading-state">Loading market history…</div>';
  };
  let marketRequest = 0,
    seriesRequest = 0;
  async function loadSeries() {
    const request = ++seriesRequest;
    clearEvidence();
    const market = $("#stat-market").value;
    prefs.market = market;
    prefs.series = $("#stat-series").value;
    prefs.resolution = $("#stat-resolution").value;
    prefs.crossplay = $("#stat-crossplay").value;
    $("#stat-summary").innerHTML = "";
    $("#stats-freshness").textContent = "Loading…";
    if (!market) {
      $("#chart-content").innerHTML = empty(
        "No observed markets yet",
        "A market appears after its first order or statistics observation. Check back after the next collection.",
      );
      $("#baseline-content").innerHTML = empty(
        "No calibration yet",
        "A selected market and sufficient history are required.",
      );
      $("#stats-freshness").textContent = "No observations";
      return;
    }
    $("#chart-content").innerHTML =
      '<div class="loading-state">Loading market history…</div>';
    const params = new URLSearchParams({
      market,
      section: prefs.series === "closed" ? "closed" : "live",
      side: prefs.series === "closed" ? "" : prefs.series,
      granularity: prefs.resolution,
      crossplay: prefs.crossplay,
      days: prefs.days || 30,
    });
    try {
      const data = await api(`statistics?${params}`);
      if (!valid() || request !== seriesRequest) return;
      $("#chart-title").textContent = titleCase(prefs.item);
      $("#chart-subtitle").textContent =
        $("#stat-market").selectedOptions[0].textContent;
      const latest = data.points.at(-1),
        fetched = data.points.length
          ? data.points.reduce(
              (max, p) => (p.fetchedAt > max ? p.fetchedAt : max),
              "",
            )
          : null;
      $("#stats-freshness").textContent = fetched
        ? `Fetched ${relative(fetched)}`
        : "No observations";
      $("#stats-freshness").title = date(fetched);
      $("#stat-summary").innerHTML = latest
        ? `<div class="stat-summary"><div><small>Latest median</small><strong>${number(latest.median)} <small>p</small></strong></div><div><small>${data.section === "closed" ? "Reported volume" : "Latest live volume"}</small><strong>${number(data.section === "closed" ? data.points.reduce((sum, p) => sum + p.volume, 0) : latest.volume)}</strong></div><div><small>Observed buckets</small><strong>${number(data.points.length)}</strong></div></div>`
        : "";
      renderChart($("#chart-content"), data);
      const budget = session.overview.baselineBudget;
      $("#baseline-content").innerHTML = data.calibrations.length
        ? `<div class="baseline-list">${data.calibrations.map((c) => `<div class="baseline-row"><div><h3>${esc(c.watch)}</h3><small>${esc(titleCase(c.rule))} · ${number(c.samples)} samples · threshold ${number(c.threshold)} · holdout ${c.holdoutRate == null ? "—" : number(c.holdoutRate * 100) + "%"}</small></div>${badge(c.status)}</div>`).join("")}</div><div class="spaced-note">Shared delivery evidence: ${esc(budget?.status || "Not established")}. A ready calibration alone does not enable delivery; all required collectors must qualify.</div>`
        : empty(
            "Still building the picture",
            "No baseline calibration exists for this selection. Configure a volume or disappearance rule to collect evidence. Fixed price rules do not need calibration.",
          );
    } catch (error) {
      if (valid() && request === seriesRequest) {
        $("#stats-freshness").textContent = "Unavailable";
        showError($("#chart-content"), error, loadSeries);
      }
    }
  }
  async function loadMarkets(slug) {
    const request = ++marketRequest;
    clearEvidence();
    ++seriesRequest;
    $("#stat-market").innerHTML = '<option value="">Loading markets…</option>';
    try {
      const markets = slug
        ? await api(`markets?item=${encodeURIComponent(slug)}`)
        : [];
      if (!valid() || request !== marketRequest) return;
      prefs.item = slug;
      $("#stat-market").innerHTML = markets.length
        ? options(
            markets.map((m) => [m.id, marketLabel(m)]),
            prefs.market,
          )
        : '<option value="">No observed markets</option>';
      await loadSeries();
    } catch (error) {
      if (valid() && request === marketRequest) {
        $("#stats-freshness").textContent = "Unavailable";
        showError($("#chart-content"), error, () => loadMarkets(slug));
      }
    }
  }
  let timer,
    searchVersion = 0;
  $("#stat-item").oninput = (event) => {
    const value = event.target.value;
    clearTimeout(timer);
    const version = ++searchVersion;
    timer = setTimeout(async () => {
      try {
        const items = await api(`items?q=${encodeURIComponent(value)}`);
        if (!valid() || version !== searchVersion) return;
        $("#stat-item-options").innerHTML = items
          .map((i) => `<option value="${esc(i.slug)}">${esc(i.name)}</option>`)
          .join("");
      } catch (error) {
        if (valid())
          showError($("#chart-content"), error, () => loadMarkets(value));
      }
    }, 220);
  };
  $("#stat-item").onchange = (event) => loadMarkets(event.target.value.trim());
  for (const id of [
    "stat-market",
    "stat-crossplay",
    "stat-series",
    "stat-resolution",
  ])
    $(`#${id}`).onchange = loadSeries;
  root.querySelectorAll("[data-days]").forEach(
    (b) =>
      (b.onclick = () => {
        prefs.days = Number(b.dataset.days);
        root
          .querySelectorAll("[data-days]")
          .forEach((x) => x.setAttribute("aria-pressed", x === b));
        loadSeries();
      }),
  );
  try {
    const items = await api("items");
    if (!valid()) return;
    $("#stat-item-options").innerHTML = items
      .map((i) => `<option value="${esc(i.slug)}">${esc(i.name)}</option>`)
      .join("");
    if (prefs.item) await loadMarkets(prefs.item);
  } catch (error) {
    if (valid()) showError($("#chart-content"), error, () => session.render());
  }
}

async function renderContracts(root, session, valid) {
  root.innerHTML = '<div class="loading-state">Loading contract catalog…</div>';
  try {
    const catalog = await api("contract-catalog");
    if (!valid()) return;
    const initial = session.config.document.contracts[0];
    root.innerHTML = `<section class="panel"><div class="filters-grid"><label>Contract type<select id="auction-kind">${options(
      [
        ["riven", "Riven"],
        ["lich", "Kuva lich"],
        ["sister", "Sister of Parvos"],
      ],
      initial?.kind || "riven",
    )}</select></label><label>Weapon<select id="auction-weapon"></select></label></div><div id="auction-content"></div><div class="spaced-note">Latest 100 stored listings for this weapon. Disappearance is not a confirmed sale; closed listings may not have sold. Asking prices are not valuations.</div></section>`;
    labelControls(root);
    let request = 0;
    async function load() {
      const version = ++request,
        kind = $("#auction-kind").value,
        weapon = $("#auction-weapon").value;
      if (!weapon) {
        $("#auction-content").innerHTML = empty(
          "No weapon manifest yet",
          "Enable contract collection on the host to populate the catalog.",
        );
        return;
      }
      $("#auction-content").innerHTML =
        '<div class="loading-state">Loading observed listings…</div>';
      try {
        const rows = await api(
          `auctions?${new URLSearchParams({ kind, weapon })}`,
        );
        if (!valid() || version !== request) return;
        $("#auction-content").innerHTML = rows.length
          ? `<div class="table-scroll"><table><thead><tr><th>CONTRACT</th><th>BUYOUT</th><th>ATTRIBUTES</th><th>STATE</th><th>OBSERVED</th></tr></thead><tbody>${rows
              .map((row) => {
                let item = {};
                try {
                  item = JSON.parse(row.item);
                } catch {}
                const attributes =
                  kind === "riven"
                    ? (item.attributes || [])
                        .map(
                          (a) =>
                            `${a.positive ? "+" : "−"} ${titleCase(a.urlName)} ${number(a.value)}`,
                        )
                        .join(" · ")
                    : [
                        item.element ? titleCase(item.element) : "",
                        item.damage != null ? `${item.damage}% damage` : "",
                        item.havingEphemera ? "Ephemera" : "",
                      ]
                        .filter(Boolean)
                        .join(" · ");
                const state = row.closed
                  ? "Closed"
                  : row.missing
                    ? "Disappeared"
                    : !row.visible
                      ? "Hidden"
                      : "Visible";
                return `<tr><td><a class="text-link" href="https://warframe.market/auction/${encodeURIComponent(row.id)}" target="_blank" rel="noopener noreferrer">${esc(item.name || titleCase(weapon))} ↗</a><small>${esc(row.id)}</small></td><td>${number(row.buyoutPrice)} p<small>Bid ${number(row.topBid)}</small></td><td>${esc(attributes || "No attributes recorded")}</td><td>${badge("paused", state)}</td><td>${esc(date(row.observedAt))}</td></tr>`;
              })
              .join("")}</tbody></table></div>`
          : empty(
              "No listings collected yet",
              "Create a watch for this weapon or enable collection across the full manifest.",
            );
      } catch (error) {
        if (valid() && version === request)
          showError($("#auction-content"), error, load);
      }
    }
    const weapons = () => {
      $("#auction-weapon").innerHTML = options(
        catalog.weapons
          .filter((w) => w.kind === $("#auction-kind").value)
          .map((w) => [w.slug, w.name]),
        initial?.weapon,
      );
      load();
    };
    $("#auction-kind").onchange = weapons;
    $("#auction-weapon").onchange = load;
    weapons();
  } catch (error) {
    if (valid()) showError(root, error, () => session.render());
  }
}
