import { $, esc, number, date, options, badge, toast } from "./core.js";

export function renderSettings(root, session) {
  const { document: doc, revision } = session.config,
    host = session.host;
  root.innerHTML = `<div class="page-head"><div><div class="eyebrow">MAKE IT YOURS</div><h1>Settings</h1><p>Control alert volume, collection coverage, and your workspace.</p></div><span class="pill">${revision ? `Revision ${revision}` : "Loaded from YAML"}</span></div>
    <div class="settings-grid"><div><section class="panel"><div class="panel-heading"><div><h2>Alert & collection preferences</h2><p>Saved to the service and applied immediately.</p></div></div><form id="settings-form" class="panel-body"><div id="settings-error" class="form-error" role="alert" hidden></div>
    <div class="setting-row"><div><h3><label for="daily-ceiling">Daily alert ceiling</label></h3><p>Maximum new admissions across every watch per UTC day. Set to zero to suppress new alerts. Pending deliveries continue.</p></div><input id="daily-ceiling" name="dailyCeiling" type="number" min="0" max="10000" step="1" required value="${doc.dailyCeiling}"></div>
    <div class="setting-row"><div><h3><label for="all-weapons">Collect all contract weapons</label></h3><p>Expand history collection to the full weapon manifest. Your watched weapons retain priority; upstream rate limits stay in place.</p>${!host.contractsEnabled ? '<p class="accent">The contract collector is disabled on this host.</p>' : ""}</div><input id="all-weapons" name="allWeapons" type="checkbox" ${doc.allWeapons ? "checked" : ""}></div>
    <div class="settings-actions"><button class="primary" type="submit">Save preferences</button></div></form></section>
    <section class="panel"><div class="panel-heading"><h2>Workspace</h2><span class="pill">This browser</span></div><div class="panel-body"><div class="setting-row"><div><h3><label for="refresh-interval">Dashboard refresh</label></h3><p>Refreshes service status and recent signals without interrupting your edits. Statistics refresh when you change their filters.</p></div><select id="refresh-interval">${options(
      [
        ["0", "Manual"],
        ["15", "15 seconds"],
        ["30", "30 seconds"],
        ["60", "1 minute"],
      ],
      session.refreshSeconds,
    )}</select></div><div class="setting-row"><div><h3>Appearance</h3><p>Compact dark workspace. Follows your browser’s font scaling and reduced-motion preferences.</p></div><span class="pill">Dark</span></div></div></section></div>
    <div><section class="panel"><div class="panel-heading"><div><h2>Host configuration</h2><p>Managed on homelab; changing these requires a restart.</p></div></div><div class="panel-body"><dl class="definition"><dt>Observer platform</dt><dd>${esc(host.platform.toUpperCase())}</dd><dt>Crossplay population</dt><dd>${host.crossplay ? "Enabled" : "Disabled"}</dd><dt>Realtime socket</dt><dd>${host.socketEnabled ? "Enabled" : "Disabled"}</dd><dt>Item book cadence</dt><dd>${esc(host.pollInterval)}</dd><dt>Statistics collection</dt><dd>${host.statisticsEnabled ? esc(host.statisticsInterval) : "Disabled"}</dd><dt>Contract collection</dt><dd>${host.contractsEnabled ? esc(host.contractInterval) : "Disabled"}</dd><dt>Notification delivery</dt><dd>${badge(host.deliveryEnabled ? "active" : "paused", host.deliveryEnabled ? "Connected" : "Not configured")}</dd><dt>Logical topics</dt><dd>${esc(host.topics.join(", ") || "None")}</dd><dt>Delivery interval</dt><dd>${esc(host.notificationInterval)}</dd><dt>Maximum send attempts</dt><dd>${number(host.maxAttempts)}</dd></dl></div><div class="spaced-note">Topic credentials and upstream limits remain on the host. Browser configuration takes precedence over the watch YAML after its first save.</div></section>
    <section class="panel"><div class="panel-heading"><h2>Configuration history</h2><span class="pill">Latest 20</span></div>${host.revisions.length ? `<div class="table-scroll"><table><thead><tr><th>REVISION</th><th>SAVED</th></tr></thead><tbody>${host.revisions.map((r) => `<tr><td>Revision ${number(r.revision)}</td><td>${esc(date(r.savedAt))}</td></tr>`).join("")}</tbody></table></div>` : '<div class="panel-body"><p class="muted">No browser edits yet. Your first save starts the revision history.</p></div>'}</section></div></div>`;
  const form = $("#settings-form");
  form.oninput = () => (session.dirty = true);
  form.onchange = () => (session.dirty = true);
  form.onsubmit = async (event) => {
    event.preventDefault();
    const button = $("button[type=submit]", form);
    button.disabled = true;
    $("#settings-error").hidden = true;
    try {
      const next = {
        ...structuredClone(doc),
        dailyCeiling: Number(form.elements.dailyCeiling.value),
        allWeapons: form.elements.allWeapons.checked,
      };
      await session.save(next, revision);
      session.dirty = false;
      toast("Preferences saved.");
      session.render();
    } catch (error) {
      $("#settings-error").textContent = error.message;
      $("#settings-error").hidden = false;
    } finally {
      button.disabled = false;
    }
  };
  $("#refresh-interval").onchange = (event) => {
    session.setRefresh(Number(event.target.value));
    toast("Browser refresh preference updated.");
  };
}
