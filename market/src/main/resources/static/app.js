import { $, api, labelControls, toast, showError } from "./core.js";
import { renderWatches, updateOverview } from "./watches.js";
import { renderSettings } from "./settings.js";
import { renderStatistics } from "./statistics.js";

const session = {
  config: null,
  host: null,
  overview: null,
  page: "watches",
  renderEpoch: 0,
  dirty: false,
  stats: {},
  refreshSeconds: 30,
};
try {
  const stored = Number(localStorage.getItem("watchdawg.refresh") ?? 30);
  if ([0, 15, 30, 60].includes(stored)) session.refreshSeconds = stored;
} catch {}
let loadVersion = 0;
session.save = async (document, revision) => {
  ++loadVersion;
  const saved = await api("configuration", { revision, document });
  if (!session.config || saved.revision >= session.config.revision)
    session.config = saved;
  // Acknowledging a committed save must not wait for status reads or replace a newer draft.
  void api("host-settings")
    .then((value) => {
      if (session.config.revision === saved.revision) session.host = value;
    })
    .catch(() => {});
  void refreshOverview();
};
session.render = () => {
  session.renderEpoch++;
  const page = session.page;
  document.querySelectorAll("[data-page]").forEach((link) => {
    if (link.dataset.page === page) link.setAttribute("aria-current", "page");
    else link.removeAttribute("aria-current");
  });
  $("#breadcrumb").textContent = page[0].toUpperCase() + page.slice(1);
  document.title = `${$("#breadcrumb").textContent} · Watchdawg`;
  $("#watch-count").textContent =
    session.config.document.watches.length +
    session.config.document.contracts.length;
  const render = {
    watches: renderWatches,
    settings: renderSettings,
    statistics: renderStatistics,
  }[page];
  Promise.resolve(render($("#content"), session)).catch((error) =>
    showError($("#content"), error, session.render),
  );
  labelControls($("#content"));
};
let interval,
  busy = false;
session.setRefresh = (seconds) => {
  session.refreshSeconds = seconds;
  try {
    localStorage.setItem("watchdawg.refresh", String(seconds));
  } catch {}
  clearInterval(interval);
  if (seconds) interval = setInterval(refreshOverview, seconds * 1000);
};
async function refreshOverview() {
  if (busy || document.hidden) return;
  busy = true;
  try {
    session.overview = await api("overview");
    if (session.page === "watches") updateOverview(session.overview);
    $("#last-refresh").textContent = "Updated just now";
  } catch {
    $("#last-refresh").textContent = "Connection lost · retrying";
  } finally {
    busy = false;
  }
}
async function load() {
  const version = ++loadVersion;
  const epoch = session.renderEpoch;
  $("#refresh").disabled = true;
  try {
    const [config, host, overview] = await Promise.all([
      api("configuration"),
      api("host-settings"),
      api("overview"),
    ]);
    if (
      version !== loadVersion ||
      session.dirty ||
      session.renderEpoch !== epoch
    )
      return;
    Object.assign(session, { config, host, overview });
    $("#last-refresh").textContent = "Updated just now";
    session.render();
  } catch (error) {
    if (
      version !== loadVersion ||
      session.dirty ||
      session.renderEpoch !== epoch
    )
      return;
    $("#last-refresh").textContent = "Connection unavailable";
    showError($("#content"), error, load);
  } finally {
    $("#refresh").disabled = false;
  }
}
function route() {
  const next = ["watches", "statistics", "settings"].includes(
    location.hash.slice(1),
  )
    ? location.hash.slice(1)
    : "watches";
  if (next === session.page && session.config) return;
  if (session.dirty && !window.confirm("Discard unsaved changes?")) {
    history.replaceState(null, "", `#${session.page}`);
    return;
  }
  session.dirty = false;
  session.page = next;
  if ($("#watch-dialog").open) $("#watch-dialog").close();
  if (session.config) session.render();
}
window.addEventListener("hashchange", route);
window.addEventListener("beforeunload", (event) => {
  if (session.dirty) {
    event.preventDefault();
    event.returnValue = "";
  }
});
$("#refresh").onclick = () => {
  if (session.dirty) {
    toast("Save or discard your edits before refreshing.", true);
    return;
  }
  load();
};
document.addEventListener("visibilitychange", () => {
  if (!document.hidden) refreshOverview();
});
route();
load();
session.setRefresh(session.refreshSeconds);
