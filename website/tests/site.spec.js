import { expect, test } from "@playwright/test";

async function revealFullPage(page) {
  const sections = page.locator("main > section");
  for (let index = 0; index < await sections.count(); index += 1) {
    await sections.nth(index).scrollIntoViewIfNeeded();
    await page.waitForTimeout(80);
  }
}

function captureRuntimeErrors(page) {
  const errors = [];
  page.on("pageerror", (error) => errors.push(error.message));
  page.on("console", (message) => {
    if (message.type() === "error") errors.push(message.text());
  });
  return errors;
}

test("desktop conversion path, model buffet, and install routes", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 1000 });
  const runtimeErrors = captureRuntimeErrors(page);

  await page.goto("/");
  await expect(page.getByRole("heading", { name: "Stop renting your own voice." })).toBeVisible();
  await expect(page.getByRole("link", { name: /Get Dictate on Google Play/i }).first()).toHaveAttribute("href", /play\.google\.com/);
  await expect(page.locator(".hero-fineprint")).toContainText("No monthly Dictate subscription");
  await page.locator(".prompt-strip").getByRole("button", { name: "Translate" }).click();
  await expect(page.locator(".demo-editor p").last()).toContainText("Pouvez-vous déplacer notre réunion");

  await revealFullPage(page);

  await page.locator("#compare").scrollIntoViewIfNeeded();
  await expect(page.getByRole("heading", { name: /voice keyboard without the monthly gatekeeper/i })).toBeVisible();
  await expect(page.locator("#monthly-hours")).toHaveValue("20");
  await expect(page.locator(".savings-card-dictate")).toContainText("$2.40");
  await expect(page.locator(".savings-card-keep")).toContainText("$12.60");
  await expect(page.locator(".savings-verdict")).toContainText("$151.20 kept per year");
  await page.locator("#compare").screenshot({ path: "output/playwright/savings-comparison.png", scale: "css" });

  await page.locator(".reword-playground").scrollIntoViewIfNeeded();
  await page.locator(".reword-prompts").getByRole("button", { name: "Bullets" }).click();
  await expect(page.locator(".reword-card-output")).toContainText("Proposal: send by Friday");
  await expect(page.locator(".capability-card")).toHaveCount(6);
  await expect(page.getByRole("heading", { name: "Transcribe existing recordings." })).toBeVisible();
  await expect(page.getByText(/choose an audio file—or a compatible video/i)).toBeVisible();
  for (const card of await page.locator(".capability-card").all()) {
    await card.scrollIntoViewIfNeeded();
  }
  await page.locator(".capability-section").screenshot({ path: "output/playwright/power-features.png", scale: "css" });

  await page.locator("#models").scrollIntoViewIfNeeded();
  await page.getByRole("tab", { name: "Rewrite LLMs" }).click();
  await expect(page.locator(".llm-route")).toHaveCount(3);
  await expect(page.locator(".llm-route strong", { hasText: "Ollama" })).toBeVisible();
  await page.locator("#models").screenshot({ path: "output/playwright/llm-buffet.png", scale: "css" });
  await page.getByRole("button", { name: "Show all 12 rewrite providers" }).click();
  await expect(page.locator(".llm-route")).toHaveCount(12);

  await page.getByRole("tab", { name: "Speech-to-text" }).click();
  await page.getByRole("button", { name: "Free offline" }).click();
  await expect(page.locator(".buffet-row")).toHaveCount(3);
  await expect(page.locator(".buffet-price.is-free")).toHaveCount(3);
  await page.locator("#models").screenshot({ path: "output/playwright/offline-buffet.png", scale: "css" });
  await page.getByRole("button", { name: "Show all 8 free offline options" }).click();
  await expect(page.locator(".buffet-row")).toHaveCount(8);

  await page.getByRole("button", { name: "All routes" }).click();
  await expect(page.locator(".buffet-row")).toHaveCount(9);
  await page.getByRole("button", { name: /Explore all \d+ mapped STT options/ }).click();
  await expect(page.locator(".buffet-row")).toHaveCount(44);

  await page.locator(".modes").scrollIntoViewIfNeeded();
  await page.getByRole("tab", { name: "Offline", exact: true }).click();
  await expect(page.getByRole("heading", { name: "Keep the audio on your phone." })).toBeVisible();

  await page.locator("#install").scrollIntoViewIfNeeded();
  await page.getByRole("tab", { name: /Build from source/ }).click();
  await expect(page.getByRole("heading", { name: "Inspect it. Fork it. Build it." })).toBeVisible();
  await expect(page.getByText("GitHub does not currently provide a signed end-user APK.")).toBeVisible();

  await page.locator(".final-cta").scrollIntoViewIfNeeded();
  await expect(page.getByRole("link", { name: "Jannis Zahn, creator and lead developer of Dictate on GitHub" })).toHaveAttribute("href", "https://github.com/DevEmperor");

  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
  expect(overflow).toBeLessThanOrEqual(1);
  expect(runtimeErrors).toEqual([]);

  await revealFullPage(page);
  await page.screenshot({ path: "output/playwright/desktop-revealed.png", fullPage: true, scale: "css" });
});

test("mobile navigation and responsive model buffet", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  const runtimeErrors = captureRuntimeErrors(page);

  await page.goto("/");
  await page.getByRole("button", { name: "Open navigation" }).click();
  await expect(page.getByRole("navigation", { name: "Mobile navigation" })).toBeVisible();
  await page.getByRole("navigation", { name: "Mobile navigation" }).getByRole("link", { name: "Models" }).click();
  await expect(page.getByRole("button", { name: "Open navigation" })).toBeVisible();

  await page.getByRole("button", { name: "Realtime" }).click();
  await expect(page.locator(".buffet-row")).toHaveCount(3);
  await expect(page.locator(".buffet-row").first()).toBeVisible();
  await page.locator("#models").screenshot({ path: "output/playwright/realtime-mobile.png", scale: "css" });

  await page.locator(".savings-calculator").scrollIntoViewIfNeeded();
  await expect(page.locator(".savings-card-dictate")).toContainText("$2.40");
  await page.locator(".savings-calculator").screenshot({ path: "output/playwright/savings-mobile.png", scale: "css" });

  for (const card of await page.locator(".capability-card").all()) {
    await card.scrollIntoViewIfNeeded();
  }
  await page.locator(".capability-section").screenshot({ path: "output/playwright/power-features-mobile.png", scale: "css" });

  await revealFullPage(page);
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
  expect(overflow).toBeLessThanOrEqual(1);
  expect(runtimeErrors).toEqual([]);

  await page.screenshot({ path: "output/playwright/mobile-revealed.png", fullPage: true, scale: "css" });
});

test("feature preview keeps its current frame until the next screenshot is decoded", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.addInitScript(() => {
    window.requestIdleCallback = () => 1;
    window.cancelIdleCallback = () => undefined;
  });

  const providerRequests = [];

  await page.route("**/media/feature-provider.png", async (route) => {
    let release;
    const gate = new Promise((resolve) => {
      release = resolve;
    });
    providerRequests.push({ release });
    await gate;
    await route.continue();
  });

  await page.goto("/", { waitUntil: "domcontentloaded" });

  const baseImage = page.locator(".feature-visual-base");
  const incomingImage = page.locator(".feature-visual-incoming");
  await expect(baseImage).toHaveAttribute("src", "/media/feature-anywhere.png");
  await expect.poll(() => baseImage.evaluate((image) => image.complete && image.naturalWidth > 0)).toBe(true);
  await page.locator(".feature-dots button").nth(1).evaluate((button) => button.click());
  await expect(page.locator(".feature-dots button").nth(1)).toHaveClass(/is-active/);

  await expect.poll(() => providerRequests.length).toBe(1);
  await expect(baseImage).toHaveAttribute("src", "/media/feature-anywhere.png");
  await expect(incomingImage).toHaveCount(1);
  await expect.poll(() => incomingImage.evaluate((image) => image.naturalWidth)).toBe(0);
  await expect.poll(() => incomingImage.evaluate((image) => Number.parseFloat(getComputedStyle(image).opacity))).toBeLessThanOrEqual(0.01);
  await expect(page.locator(".feature-visual figure")).toHaveCount(1);

  providerRequests[0].release();
  await expect.poll(() => providerRequests.length).toBe(2);
  await expect(baseImage).toHaveAttribute("src", "/media/feature-provider.png");
  await expect.poll(() => baseImage.evaluate((image) => image.naturalWidth)).toBe(0);
  await expect.poll(() => incomingImage.evaluate((image) => image.naturalWidth)).toBeGreaterThan(0);
  await expect.poll(() => incomingImage.evaluate((image) => Number.parseFloat(getComputedStyle(image).opacity))).toBeGreaterThanOrEqual(0.99);

  await page.waitForTimeout(320);
  await expect(incomingImage).toHaveCount(1);
  await expect.poll(() => incomingImage.evaluate((image) => Number.parseFloat(getComputedStyle(image).opacity))).toBeGreaterThanOrEqual(0.99);

  providerRequests[1].release();
  await expect.poll(() => baseImage.evaluate((image) => image.complete && image.naturalWidth > 0)).toBe(true);
  await expect(incomingImage).toHaveCount(0);
  await expect(page.locator(".feature-visual figure")).toHaveCount(1);
});
