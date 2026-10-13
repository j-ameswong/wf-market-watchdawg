import { test, expect } from "@playwright/test";

const headers = { "X-Watchdawg-Request": "1" };
const itemWatch = (name = "khra-price", rule = "underpriced") => ({
  name,
  item: "khra",
  rank: "3",
  maxUnitPrice: 15,
  rule,
  priority: "HIGH",
  cooldown: "PT1H",
  topic: "default",
  enabled: true,
});
const current = async (request) =>
  (await request.get("/api/operator/configuration")).json();
async function replace(request, document) {
  const before = await current(request);
  const response = await request.put("/api/operator/configuration", {
    headers,
    data: { revision: before.revision, document },
  });
  expect(response.ok(), await response.text()).toBeTruthy();
  return response.json();
}

test.beforeEach(async ({ request }) => {
  await replace(request, {
    watches: [],
    contracts: [],
    dailyCeiling: 50,
    allWeapons: false,
  });
});

test("create, edit, pause, resume and delete a real persisted item watch", async ({
  page,
  request,
}) => {
  const errors = [];
  page.on("pageerror", (error) => errors.push(error.message));
  await page.goto("/");
  await expect(
    page.getByRole("heading", { name: "Keep an eye on your first market" }),
  ).toBeVisible();
  await page.getByRole("button", { name: "New watch" }).click();
  const editor = page.getByRole("dialog");
  await editor.getByLabel("Item", { exact: true }).fill("khra");
  await editor.getByLabel("Item", { exact: true }).press("Tab");
  await editor.getByLabel("Rank", { exact: true }).selectOption("3");
  await editor.getByLabel("Watch name").fill("cheap-khra");
  await editor.getByLabel("Maximum unit price (p)").fill("12.5");
  await editor.getByRole("button", { name: "Create watch" }).click();
  await expect(editor).not.toBeVisible();
  await expect(page.getByText("cheap-khra", { exact: true })).toBeVisible();
  await page.reload();
  await expect(page.getByText("cheap-khra", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "Edit cheap-khra" }).click();
  await editor.getByLabel("Maximum unit price (p)").fill("10");
  await editor.getByRole("button", { name: "Save changes" }).click();
  await expect(editor).not.toBeVisible();
  await page.getByRole("button", { name: "Pause cheap-khra" }).click();
  await expect(
    page.getByRole("button", { name: "Resume cheap-khra" }),
  ).toBeVisible();
  expect((await current(request)).document.watches[0].enabled).toBe(false);
  await page.getByRole("button", { name: "Resume cheap-khra" }).click();
  await expect(
    page.getByRole("button", { name: "Pause cheap-khra" }),
  ).toBeVisible();
  await page.getByRole("button", { name: "Edit cheap-khra" }).click();
  page.once("dialog", (dialog) => dialog.accept());
  await editor.getByRole("button", { name: "Delete watch" }).click();
  await expect(editor).not.toBeVisible();
  expect((await current(request)).document.watches).toHaveLength(0);
  expect(errors).toEqual([]);
});

test("riven and nemesis filters persist through their forms", async ({
  page,
  request,
}) => {
  await page.goto("/");
  await page.getByRole("button", { name: "New watch" }).click();
  let editor = page.getByRole("dialog");
  await editor.getByLabel("Watch type").selectOption("riven");
  await editor.getByLabel("Weapon", { exact: true }).selectOption("soma");
  await editor.getByLabel("Maximum buyout (p)").fill("150");
  await editor.getByRole("button", { name: "Add attribute" }).click();
  await editor
    .getByLabel("Positive attribute", { exact: true })
    .selectOption("recoil");
  await editor.getByLabel("Minimum benefit").fill("20");
  await editor.getByLabel("Required negative attributes").selectOption("zoom");
  await editor.getByLabel("Maximum rerolls").fill("8");
  await editor.getByRole("button", { name: "Create watch" }).click();
  await expect(editor).not.toBeVisible();
  let doc = (await current(request)).document;
  expect(doc.contracts[0].positive).toEqual({ recoil: 20 });
  expect(doc.contracts[0].negative).toEqual(["zoom"]);
  await page.getByRole("button", { name: "New watch" }).click();
  await editor.getByLabel("Watch type").selectOption("sister");
  await editor
    .getByLabel("Weapon", { exact: true })
    .selectOption("tenet_arca_plasmor");
  await editor.getByLabel("Element", { exact: true }).selectOption("toxin");
  await editor.getByLabel("Minimum damage (%)").fill("55");
  await editor.getByLabel("Ephemera", { exact: true }).selectOption("true");
  await editor.getByRole("button", { name: "Create watch" }).click();
  await expect(editor).not.toBeVisible();
  doc = (await current(request)).document;
  expect(doc.contracts[1]).toMatchObject({
    kind: "sister",
    element: "toxin",
    minDamage: 55,
    ephemera: true,
    maxPrice: null,
  });
});

test("invalid rules and conflicting tabs preserve the draft and saved state", async ({
  page,
  request,
}) => {
  await replace(request, {
    watches: [itemWatch()],
    contracts: [],
    dailyCeiling: 50,
    allWeapons: false,
  });
  await page.goto("/");
  await page.getByRole("button", { name: "Edit khra-price" }).click();
  const editor = page.getByRole("dialog");
  await editor.getByLabel("Rule", { exact: true }).selectOption("spread");
  await editor.getByRole("button", { name: "Save changes" }).click();
  await expect(editor.getByRole("alert")).toContainText("spread needs");
  await editor.getByLabel("Minimum spread (p)").fill("5");
  const external = await current(request);
  external.document.dailyCeiling = 20;
  await replace(request, external.document);
  await editor.getByRole("button", { name: "Save changes" }).click();
  await expect(editor.getByRole("alert")).toContainText(
    "Configuration changed",
  );
  await expect(editor.getByLabel("Minimum spread (p)")).toHaveValue("5");
  expect((await current(request)).document.dailyCeiling).toBe(20);
});

test("settings save operational preferences without changing watches", async ({
  page,
  request,
}) => {
  await replace(request, {
    watches: [itemWatch()],
    contracts: [],
    dailyCeiling: 50,
    allWeapons: false,
  });
  await page.goto("/#settings");
  await page.getByLabel("Daily alert ceiling", { exact: true }).fill("25");
  await page
    .getByLabel("Collect all contract weapons", { exact: true })
    .check();
  await page.getByRole("button", { name: "Save preferences" }).click();
  await expect(page.getByRole("status")).toContainText("Preferences saved");
  const doc = (await current(request)).document;
  expect(doc.dailyCeiling).toBe(25);
  expect(doc.allWeapons).toBe(true);
  expect(doc.watches).toHaveLength(1);
  await page
    .getByLabel("Dashboard refresh", { exact: true })
    .selectOption("60");
  await page.reload();
  await expect(
    page.getByLabel("Dashboard refresh", { exact: true }),
  ).toHaveValue("60");
});

test("statistics filter populations, expose empty states and support keyboard chart inspection", async ({
  page,
  request,
}) => {
  await replace(request, {
    watches: [itemWatch()],
    contracts: [],
    dailyCeiling: 50,
    allWeapons: false,
  });
  await page.goto("/#statistics");
  await page
    .getByLabel("Market", { exact: true })
    .selectOption({ label: "PC · Rank 3" });
  await expect(
    page.getByRole("img", { name: /Median platinum/ }),
  ).toBeVisible();
  await page.getByLabel(/Inspect a bucket/).focus();
  await page.keyboard.press("ArrowLeft");
  await expect(page.locator("#chart-inspector")).toContainText("Median");
  const path = await page.locator(".price-line").getAttribute("d");
  expect((path.match(/M/g) || []).length).toBe(2);
  await page.getByLabel("Population", { exact: true }).selectOption("false");
  await expect(page.locator("#chart-inspector")).toContainText("80 p");
  await page.getByLabel("Series", { exact: true }).selectOption("sell");
  await expect(
    page.getByRole("heading", { name: "No observations in this selection" }),
  ).toBeVisible();
});

test("backend outages are explicit and retry recovers", async ({ page }) => {
  await page.route("**/api/operator/overview", (route) =>
    route.fulfill({
      status: 503,
      json: { message: "Temporarily unavailable" },
    }),
  );
  await page.goto("/");
  await expect(page.getByRole("alert")).toContainText(
    "Temporarily unavailable",
  );
  await page.unroute("**/api/operator/overview");
  await page.getByRole("button", { name: "Try again" }).click();
  await expect(
    page.getByRole("heading", { name: "Market watches", exact: true }),
  ).toBeVisible();
});

test("refresh responses do not overwrite settings edited while loading", async ({
  page,
}) => {
  await page.goto("/#settings");
  await expect(
    page.getByLabel("Daily alert ceiling", { exact: true }),
  ).toHaveValue("50");
  let release;
  const held = new Promise((resolve) => (release = resolve));
  let started;
  const waiting = new Promise((resolve) => (started = resolve));
  await page.route("**/api/operator/configuration", async (route) => {
    const response = await route.fetch();
    started();
    await held;
    await route.fulfill({ response });
  });
  await page.getByRole("button", { name: "Refresh dashboard" }).click();
  await waiting;
  await page.getByLabel("Daily alert ceiling", { exact: true }).fill("33");
  release();
  await expect(
    page.getByRole("button", { name: "Refresh dashboard" }),
  ).toBeEnabled();
  await expect(
    page.getByLabel("Daily alert ceiling", { exact: true }),
  ).toHaveValue("33");
});

test("late editor lookups cannot change another watch's dimensions", async ({
  page,
  request,
}) => {
  await replace(request, {
    watches: [itemWatch("first"), { ...itemWatch("second"), rank: "0" }],
    contracts: [],
    dailyCeiling: 50,
  });
  await page.goto("/");
  let release, started;
  const held = new Promise((resolve) => (release = resolve));
  const waiting = new Promise((resolve) => (started = resolve));
  let first = true;
  await page.route("**/api/operator/items?q=khra", async (route) => {
    const response = await route.fetch();
    if (first) {
      first = false;
      started();
      await held;
    }
    await route.fulfill({ response });
  });
  await page.getByRole("button", { name: "Edit first", exact: true }).click();
  await waiting;
  await page.getByRole("button", { name: "Close watch editor" }).click();
  await page.getByRole("button", { name: "Edit second", exact: true }).click();
  await expect(page.getByLabel("Rank", { exact: true })).toHaveValue("0");
  release();
  await page.waitForResponse("**/api/operator/items?q=khra");
  await expect(page.getByLabel("Rank", { exact: true })).toHaveValue("0");
});

test("failed population changes clear previous calibration evidence", async ({
  page,
  request,
}) => {
  await replace(request, {
    watches: [itemWatch()],
    contracts: [],
    dailyCeiling: 50,
  });
  await page.route("**/api/operator/statistics?*", async (route) => {
    if (
      new URL(route.request().url()).searchParams.get("crossplay") === "false"
    ) {
      await route.fulfill({
        status: 503,
        json: { message: "Statistics unavailable" },
      });
      return;
    }
    const response = await route.fetch();
    const body = await response.json();
    body.calibrations = [
      {
        watch: "qualified",
        rule: "volume-spike",
        samples: 40,
        threshold: 2,
        holdoutRate: 0.1,
        status: "ready",
      },
    ];
    await route.fulfill({ json: body });
  });
  await page.goto("/#statistics");
  await page
    .getByLabel("Market", { exact: true })
    .selectOption({ label: "PC · Rank 3" });
  await expect(page.locator("#baseline-content")).toContainText("Ready");
  await page.getByLabel("Population", { exact: true }).selectOption("false");
  await expect(page.getByRole("alert")).toContainText("Statistics unavailable");
  await expect(page.locator("#baseline-content")).not.toContainText("Ready");
  await expect(page.locator("#stat-summary")).toBeEmpty();
});

test("sparse charts keep isolated observations visible above one hundred buckets", async ({
  page,
  request,
}) => {
  await replace(request, {
    watches: [itemWatch()],
    contracts: [],
    dailyCeiling: 50,
  });
  await page.route("**/api/operator/statistics?*", async (route) => {
    const response = await route.fetch(),
      body = await response.json();
    body.granularity = "hourly";
    body.points = Array.from({ length: 101 }, (_, i) => ({
      bucket: new Date(Date.now() - (101 - i) * 10800000).toISOString(),
      median: 10,
      volume: 3,
      minPrice: 8,
      maxPrice: 12,
      avgPrice: 10,
      fetchedAt: new Date().toISOString(),
    }));
    await route.fulfill({ json: body });
  });
  await page.goto("/#statistics");
  await page
    .getByLabel("Market", { exact: true })
    .selectOption({ label: "PC · Rank 3" });
  await expect(page.locator(".chart circle")).toHaveCount(102);
});

test("desktop and mobile layouts have no document overflow and render cleanly", async ({
  page,
  request,
}, testInfo) => {
  await replace(request, {
    watches: [
      itemWatch("khra-price"),
      {
        ...itemWatch("serration-max"),
        item: "serration",
        subtype: "base",
        rank: "10",
        maxUnitPrice: 18,
      },
      { ...itemWatch("khra-volume", "volume-spike"), priority: "DEFAULT" },
    ],
    contracts: [
      {
        name: "soma-critical",
        kind: "riven",
        weapon: "soma",
        maxPrice: 200,
        priority: "HIGH",
        enabled: true,
      },
    ],
    dailyCeiling: 50,
    allWeapons: false,
  });
  await page.goto("/");
  await expect(page.getByText("soma-critical", { exact: true })).toBeVisible();
  await page.screenshot({
    path: testInfo.outputPath("watches-desktop.png"),
    fullPage: true,
  });
  await page.getByRole("link", { name: "Statistics" }).click();
  await page
    .getByLabel("Market", { exact: true })
    .selectOption({ label: "PC · Rank 3" });
  await expect(
    page.getByRole("img", { name: /Median platinum/ }),
  ).toBeVisible();
  await page.screenshot({
    path: testInfo.outputPath("statistics-desktop.png"),
    fullPage: true,
  });
  await page.setViewportSize({ width: 390, height: 844 });
  await page.getByRole("link", { name: /Watches/ }).click();
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= window.innerWidth,
    ),
  ).toBeTruthy();
  await page.screenshot({
    path: testInfo.outputPath("watches-mobile.png"),
    fullPage: true,
  });
  await page.getByRole("button", { name: "New watch" }).click();
  await expect(page.getByRole("dialog")).toBeVisible();
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= window.innerWidth,
    ),
  ).toBeTruthy();
});
