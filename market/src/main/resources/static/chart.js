import { $, esc, number, date, empty } from "./core.js";

/** Plot observed buckets only. Break the line across missing intervals; never fabricate samples. */
export function renderChart(root, data) {
  const points = data.points;
  if (!points.length) {
    root.innerHTML = empty(
      "No observations in this selection",
      "Try another market, population, series or date range. New watches collect statistics on the next scheduled pass.",
    );
    return;
  }
  const width = 920,
    height = 290,
    left = 54,
    right = 20,
    top = 16,
    bottom = 38,
    priceHeight = 175;
  const times = points.map((p) => Date.parse(p.bucket));
  const first = Math.min(...times),
    last = Math.max(...times);
  const low = Math.min(...points.map((p) => Number(p.median))),
    high = Math.max(...points.map((p) => Number(p.median)));
  const pad = Math.max((high - low) * 0.18, high * 0.03, 1),
    min = Math.max(0, low - pad),
    max = high + pad;
  const x = (time) =>
    left +
    (last === first ? 0.5 : (time - first) / (last - first)) *
      (width - left - right);
  const y = (price) => top + ((max - price) / (max - min)) * priceHeight;
  const volMax = Math.max(1, ...points.map((p) => Number(p.volume))),
    baseline = height - bottom;
  const step = data.granularity === "daily" ? 86400000 : 3600000;
  let path = "";
  points.forEach(
    (point, index) =>
      (path += `${index === 0 || times[index] - times[index - 1] > step * 1.5 ? "M" : "L"}${x(times[index]).toFixed(2)},${y(Number(point.median)).toFixed(2)} `),
  );
  const ticks = Array.from(
    { length: 5 },
    (_, index) => min + ((max - min) * index) / 4,
  );
  const dateTicks = [0, 0.25, 0.5, 0.75, 1].map(
    (f) => first + (last - first) * f,
  );
  root.innerHTML = `<div class="chart-wrap"><svg class="chart" viewBox="0 0 ${width} ${height}" role="img" aria-label="Median platinum price and observed volume over time. Missing buckets are left as gaps.">
    ${ticks.map((t) => `<line class="gridline" x1="${left}" x2="${width - right}" y1="${y(t)}" y2="${y(t)}"/><text x="${left - 10}" y="${y(t) + 4}" text-anchor="end">${number(t)}</text>`).join("")}
    ${dateTicks.map((t, i) => `<text x="${x(t)}" y="${height - 10}" text-anchor="${i === 0 ? "start" : i === 4 ? "end" : "middle"}">${esc(new Date(t).toLocaleDateString(undefined, { month: "short", day: "numeric" }))}</text>`).join("")}
    ${points.map((p, i) => `<rect class="volume-bar" x="${x(times[i]) - Math.min(8, ((width - left - right) / points.length) * 0.3)}" y="${baseline - (Number(p.volume) / volMax) * 40}" width="${Math.max(1, Math.min(16, ((width - left - right) / points.length) * 0.6))}" height="${(Number(p.volume) / volMax) * 40}" rx="1"/>`).join("")}
    <path class="price-line" d="${path}"/>${points.map((p, i) => `<circle cx="${x(times[i])}" cy="${y(Number(p.median))}" r="${points.length < 20 ? 3 : 2}"><title>${esc(date(p.bucket))}: ${number(p.median)} p</title></circle>`).join("")}<circle id="chart-focus" class="focus-dot" r="5" cx="${x(last)}" cy="${y(Number(points.at(-1).median))}"/></svg></div>
    <div class="chart-legend"><span><i class="legend-dot"></i>Median price (p)</span><span><i class="legend-dot volume"></i>${data.section === "closed" ? "Reported trade volume" : "Live order volume"}</span><span>${number(points.length)} observed buckets</span></div><div class="chart-inspector" id="chart-inspector" aria-live="polite"></div><div class="chart-scrubber"><label for="chart-bucket">Inspect a bucket · use arrow keys<input id="chart-bucket" type="range" min="0" max="${points.length - 1}" value="${points.length - 1}" step="1"></label></div>
    <details><summary>View observed data as a table</summary><div class="table-scroll"><table><thead><tr><th>BUCKET</th><th>MEDIAN</th><th>AVERAGE</th><th>MIN / MAX</th><th>VOLUME</th><th>FETCHED</th></tr></thead><tbody>${points.map((p) => `<tr><td>${esc(date(p.bucket))}</td><td>${number(p.median)} p</td><td>${number(p.avgPrice)} p</td><td>${number(p.minPrice)} / ${number(p.maxPrice)}</td><td>${number(p.volume)}</td><td>${esc(date(p.fetchedAt))}</td></tr>`).join("")}</tbody></table></div></details>`;
  const inspect = (index) => {
    const p = points[index];
    $("#chart-inspector", root).innerHTML =
      `<span>${esc(date(p.bucket))}</span><span>Median <strong>${number(p.median)} p</strong></span><span>Range ${number(p.minPrice)}–${number(p.maxPrice)} p</span><span>Volume ${number(p.volume)}</span>`;
    $("#chart-focus", root).setAttribute("cx", x(times[index]));
    $("#chart-focus", root).setAttribute("cy", y(Number(p.median)));
    $("#chart-bucket", root).value = index;
  };
  $("#chart-bucket", root).oninput = (event) =>
    inspect(Number(event.target.value));
  $(".chart", root).onpointermove = (event) => {
    const rect = event.currentTarget.getBoundingClientRect(),
      pos = ((event.clientX - rect.left) / rect.width) * width;
    const time =
      first + ((pos - left) / (width - left - right)) * (last - first);
    let index = 0;
    for (let i = 1; i < times.length; i++)
      if (Math.abs(times[i] - time) < Math.abs(times[index] - time)) index = i;
    inspect(index);
  };
  inspect(points.length - 1);
}
