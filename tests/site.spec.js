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
    if (message.type() !== "error") return;
    const text = message.text();
    // The headless shell lacks proprietary H.264, so the demo <video> fails to decode — that is an
    // environment codec limitation, not a site defect. Ignore media-load noise only.
    if (/demo\.mp4|MEDIA_ELEMENT|could not be decoded|Failed to load because no supported source/i.test(text)) return;
    errors.push(text);
  });
  return errors;
}

test("desktop conversion path, model buffet, and install", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 1000 });
  const runtimeErrors = captureRuntimeErrors(page);

  await page.goto("/");
  await expect(page.getByRole("heading", { name: "Your voice. Any model. One keyboard." })).toBeVisible();
  await expect(page.getByRole("link", { name: /Get it on Google Play/i }).first()).toHaveAttribute("href", /play\.google\.com/);
  await expect(page.locator(".hero-fineprint")).toContainText("No subscription");
  await page.locator(".prompt-strip").getByRole("button", { name: "Translate" }).click();
  await expect(page.locator(".demo-editor p").last()).toContainText("Pouvez-vous déplacer notre réunion");

  await revealFullPage(page);

  await page.locator("#compare").scrollIntoViewIfNeeded();
  await expect(page.getByRole("heading", { name: /without the monthly bill/i })).toBeVisible();
  await expect(page.locator(".freedom-columns")).toContainText("WISPR FLOW PRO");
  await expect(page.locator("#monthly-hours")).toHaveValue("20");
  await expect(page.locator(".savings-card-dictate")).toContainText("$2.40");
  await expect(page.locator(".savings-card-keep")).toContainText("$12.60");
  await expect(page.locator(".savings-verdict")).toContainText("$151.20 kept per year");

  await page.locator(".reword-playground").scrollIntoViewIfNeeded();
  await page.locator(".reword-prompts").getByRole("button", { name: "Bullets" }).click();
  await expect(page.locator(".reword-card-output")).toContainText("Proposal: send by Friday");
  await expect(page.locator(".capability-card")).toHaveCount(7);
  await expect(page.getByRole("heading", { name: "Transcribe existing recordings." })).toBeVisible();

  await page.locator("#models").scrollIntoViewIfNeeded();
  await page.getByRole("tab", { name: "Rewrite LLMs" }).click();
  await expect(page.locator(".llm-route")).toHaveCount(7);
  await expect(page.locator(".llm-route", { hasText: "Ollama" })).toBeVisible();
  await page.getByRole("button", { name: "Show all 12 rewrite providers" }).click();
  await expect(page.locator(".llm-route")).toHaveCount(12);

  await page.getByRole("tab", { name: "Speech-to-text" }).click();
  await page.locator(".buffet-filters").getByRole("button", { name: "Free offline" }).click();
  await expect(page.locator(".buffet-row")).toHaveCount(3);
  await expect(page.locator(".buffet-price.is-free")).toHaveCount(3);
  await page.getByRole("button", { name: "Show all 8 free offline options" }).click();
  await expect(page.locator(".buffet-row")).toHaveCount(8);

  await page.locator(".buffet-filters").getByRole("button", { name: "All routes" }).click();
  await expect(page.locator(".buffet-row")).toHaveCount(12);
  await page.getByRole("button", { name: /Explore all \d+ mapped STT options/ }).click();
  await expect(page.locator(".buffet-row")).toHaveCount(44);

  await page.locator(".modes").scrollIntoViewIfNeeded();
  await page.getByRole("tab", { name: "Offline", exact: true }).click();
  await expect(page.getByRole("heading", { name: "Keep the audio on your phone." })).toBeVisible();

  await page.locator("#install").scrollIntoViewIfNeeded();
  await expect(page.getByRole("heading", { name: "The shortest route to your first sentence." })).toBeVisible();
  await expect(page.locator(".install-box").getByRole("link", { name: /Get it on Google Play/i })).toHaveAttribute("href", /play\.google\.com/);
  await expect(page.locator(".install-oss-note")).toContainText("Apache 2.0");

  await page.locator(".final-cta").scrollIntoViewIfNeeded();
  await expect(page.getByRole("link", { name: "Jannis Zahn, creator and lead developer of Dictate Keyboard on GitHub" })).toHaveAttribute("href", "https://github.com/DevEmperor");

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

  await page.locator("#models").scrollIntoViewIfNeeded();
  await page.locator(".buffet-filters").getByRole("button", { name: "Realtime" }).click();
  await expect(page.locator(".buffet-row")).toHaveCount(4);
  await expect(page.locator(".buffet-row").first()).toBeVisible();

  await page.locator(".savings-calculator").scrollIntoViewIfNeeded();
  await expect(page.locator(".savings-card-dictate")).toContainText("$2.40");

  await revealFullPage(page);
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
  expect(overflow).toBeLessThanOrEqual(1);
  expect(runtimeErrors).toEqual([]);

  await page.screenshot({ path: "output/playwright/mobile-revealed.png", fullPage: true, scale: "css" });
});

test("animated feature visuals switch with the dots", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  const runtimeErrors = captureRuntimeErrors(page);

  await page.goto("/");
  await page.locator("#features").scrollIntoViewIfNeeded();

  const stage = page.locator(".feature-visual-stage");
  await expect(stage).toHaveClass(/stage-keyboard/);

  await page.locator(".feature-dots button").nth(1).click();
  await expect(page.locator(".feature-dots button").nth(1)).toHaveClass(/is-active/);
  await expect(stage).toHaveClass(/stage-route/);

  await page.locator(".feature-dots button").nth(4).click();
  await expect(stage).toHaveClass(/stage-wear/);

  expect(runtimeErrors).toEqual([]);
});
