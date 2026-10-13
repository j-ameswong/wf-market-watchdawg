export const $ = (selector, root = document) => root.querySelector(selector);
// Keep names independent of option text and explanatory copy inside the visual label.
export function labelControls(root) {
  for (const label of root.querySelectorAll("label")) {
    const control = label.querySelector("input,select,textarea");
    const name = [...label.childNodes]
      .filter((node) => node.nodeType === Node.TEXT_NODE)
      .map((node) => node.textContent.trim())
      .filter(Boolean)
      .join(" ");
    if (control && name && !control.hasAttribute("aria-label"))
      control.setAttribute("aria-label", name);
  }
}
export const esc = (value) =>
  String(value ?? "").replace(
    /[&<>"']/g,
    (c) =>
      ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[
        c
      ],
  );
export const number = (value) =>
  value == null
    ? "—"
    : new Intl.NumberFormat(undefined, { maximumFractionDigits: 2 }).format(
        value,
      );
export const date = (value) =>
  value
    ? new Date(value).toLocaleString(undefined, {
        month: "short",
        day: "numeric",
        hour: "2-digit",
        minute: "2-digit",
      })
    : "—";
export const relative = (value) => {
  if (!value) return "Not observed";
  const seconds = Math.max(0, (Date.now() - new Date(value).getTime()) / 1000);
  return seconds < 60
    ? "Just now"
    : seconds < 3600
      ? `${Math.floor(seconds / 60)}m ago`
      : seconds < 86400
        ? `${Math.floor(seconds / 3600)}h ago`
        : `${Math.floor(seconds / 86400)}d ago`;
};
export const titleCase = (value) =>
  String(value || "")
    .replaceAll("_", " ")
    .replaceAll("-", " ")
    .replace(/\b\w/g, (c) => c.toUpperCase());
export const rules = {
  underpriced: "Underpriced listing",
  "price-crossing": "Price crossing",
  spread: "Spread opportunity",
  "volume-spike": "Volume spike",
  "vanish-fast": "Fast disappearance",
};
export const empty = (title, description, action = "") =>
  `<div class="empty"><div class="empty-icon" aria-hidden="true">◇</div><h3>${esc(title)}</h3><p>${esc(description)}</p>${action}</div>`;
export const badge = (status, label = titleCase(status)) =>
  `<span class="pill ${esc(status)}">${esc(label)}</span>`;
export const options = (values, selected) =>
  values
    .map((value) => {
      const [id, label] = Array.isArray(value)
        ? value
        : [value, titleCase(value)];
      return `<option value="${esc(id)}" ${String(id) === String(selected) ? "selected" : ""}>${esc(label)}</option>`;
    })
    .join("");
export function toast(message, error = false) {
  const node = $("#toast");
  node.textContent = message;
  node.classList.toggle("error", error);
  node.hidden = false;
  clearTimeout(toast.timer);
  toast.timer = setTimeout(() => (node.hidden = true), error ? 9000 : 4000);
}
export async function api(path, body) {
  const response = await fetch(`/api/operator/${path}`, {
    method: body === undefined ? "GET" : "PUT",
    headers:
      body === undefined
        ? {}
        : { "Content-Type": "application/json", "X-Watchdawg-Request": "1" },
    body: body === undefined ? undefined : JSON.stringify(body),
    signal: AbortSignal.timeout(15000),
  });
  if (!response.ok) {
    const payload = await response.json().catch(() => ({}));
    throw new Error(
      payload.message ||
        (response.status === 409
          ? "Configuration changed in another tab. Reload before saving again."
          : `Request failed (${response.status}). Please retry.`),
    );
  }
  return response.json();
}
export function marketLabel(market) {
  return [
    String(market.platform).toUpperCase(),
    market.subtype,
    ...["rank", "charges", "amberStars", "cyanStars"]
      .filter((k) => market[k] != null)
      .map((k) => `${titleCase(k.replace(/([A-Z])/g, " $1"))} ${market[k]}`),
  ]
    .filter(Boolean)
    .join(" · ");
}
export function metric(label, value, note, textual = false) {
  return `<div class="metric"><div class="metric-label">${esc(label)}</div><div class="metric-value ${textual ? "textual" : ""}">${value}</div><div class="metric-note">${esc(note)}</div></div>`;
}
export function showError(container, error, retry) {
  container.innerHTML = `<div class="notice error" role="alert">${esc(error.message)} <button data-retry>Try again</button></div>`;
  $("[data-retry]", container).onclick = retry;
}
