// Optional browser regression: run a local app, install Playwright in your test
// environment, then: node scripts/test-demo-history.cjs http://localhost:8080
const assert = require('node:assert/strict');
const { chromium } = require('playwright');

const base = (process.argv[2] || 'http://localhost:8080').replace(/\/$/, '');
const evaluationApi = `${base}/api/v1/underwriting/evaluations`;

(async () => {
  const browser = await chromium.launch({
    headless: true,
    ...(process.env.CHROMIUM_EXECUTABLE_PATH ? { executablePath: process.env.CHROMIUM_EXECUTABLE_PATH } : {}),
    args: ['--no-sandbox']
  });
  const results = [];
  let failed = false;

  async function createEvaluation(request, policyNo = 'P-1001') {
    const response = await request.post(evaluationApi, {
      data: { policyNo, question: '历史恢复浏览器回归验证。' }
    });
    assert.equal(response.status(), 201);
    return response.json();
  }

  async function historyPage(run) {
    const context = await browser.newContext();
    const errors = [];
    try {
      const saved = await createEvaluation(context.request);
      const older = await createEvaluation(context.request);
      await context.addInitScript(({ saved, older }) => {
        sessionStorage.setItem('underwriting-demo-history-v1', JSON.stringify([saved, older].map(evaluation => ({
          id: evaluation.id, policyNo: evaluation.policyNo, scenarioName: '历史案件',
          displayTime: '12:00:00', durationMs: 10, evaluation, review: null
        }))));
      }, { saved, older });
      const page = await context.newPage();
      page.on('pageerror', error => errors.push(error.message));
      await page.goto(`${base}/demo/`);
      await page.locator('#scenario-detail').waitFor({ state: 'visible' });
      await run({ page, context, saved, older });
      assert.deepEqual(errors, [], 'Browser must not have uncaught page errors');
    } finally {
      await context.close();
    }
  }

  async function check(name, run) {
    try {
      await historyPage(run);
      results.push({ name, pass: true });
    } catch (error) {
      failed = true;
      results.push({ name, pass: false, error: error.message });
    }
  }

  const restore = async (page, id) => {
    await page.locator(`#history-list button[title="恢复评估 ${id}"]`).click();
    await page.waitForFunction(() => !state.savedEvaluationController && !state.detailController);
    if (await page.locator('#result-content').isVisible()) {
      await page.getByRole('tab', { name: '人工复核' }).click();
    }
  };
  const reportId = async page => {
    const href = await page.locator('#download-report').getAttribute('href');
    return href?.split('/').at(-2);
  };

  try {
    await check('history reads a review saved by another client without rerunning Agent', async ({ page, context, saved }) => {
      const before = await (await context.request.get(evaluationApi)).json();
      const review = await context.request.post(`${evaluationApi}/${saved.id}/review`, {
        data: { reviewerId: 'UW-OTHER-TAB', outcome: 'APPROVED', comment: '其他标签页已经完成复核。', conditions: [] }
      });
      assert.equal(review.status(), 201);
      await restore(page, saved.id);
      assert.equal(await page.locator('#review-form').isVisible(), false, 'History must show the saved immutable review');
      assert.match(await page.locator('#review-result').innerText(), /UW-OTHER-TAB/);
      assert.equal(await reportId(page), saved.id);
      assert.equal((await (await context.request.get(evaluationApi)).json()).length, before.length);
    });

    await check('history supersedes an evaluation already in flight', async ({ page, saved }) => {
      let release;
      const gate = new Promise(resolve => { release = resolve; });
      let entered;
      const started = new Promise(resolve => { entered = resolve; });
      await page.route('**/api/v1/underwriting/evaluations', async route => {
        if (route.request().method() !== 'POST') return route.continue();
        const response = await route.fetch();
        entered();
        await gate;
        await route.fulfill({ response }).catch(() => {});
      });
      await page.locator('#run-evaluation').click();
      await started;
      try {
        await restore(page, saved.id);
      } finally { release(); }
      await page.waitForFunction(() => !state.evaluationController);
      assert.equal(await reportId(page), saved.id, 'Late evaluation must not replace explicitly restored history');
      assert.equal(await page.locator('#run-evaluation').innerText(), '▶ 运行智能核保', 'Restoration must reset the canceled run button');
    });

    await check('newer history selection wins over a delayed older open', async ({ page, saved, older }) => {
      let release;
      const gate = new Promise(resolve => { release = resolve; });
      let entered;
      const started = new Promise(resolve => { entered = resolve; });
      await page.route(`**/api/v1/underwriting/evaluations/${older.id}`, async route => {
        entered();
        await gate;
        await route.continue().catch(() => {});
      });
      await page.locator(`#history-list button[title="恢复评估 ${older.id}"]`).click();
      // The old implementation never reads saved history from the server.
      const canonicalRead = await Promise.race([started.then(() => true), page.evaluate(() => new Promise(resolve => setTimeout(() => resolve(false), 1000)))]);
      try {
        assert.equal(canonicalRead, true, 'History must load its canonical saved record');
        await restore(page, saved.id);
      } finally { release(); }
      await page.waitForFunction(id => document.querySelector('#evaluation-status').textContent.includes(id), saved.id);
      assert.equal(await reportId(page), saved.id);
    });

    await check('missing saved history cannot offer a writable cached result', async ({ page, saved }) => {
      await page.route(`**/api/v1/underwriting/evaluations/${saved.id}`, route => route.fulfill({
        status: 404, contentType: 'application/json',
        body: JSON.stringify({ errorCode: 'EVALUATION_NOT_FOUND', detail: 'Saved evaluation is no longer available' })
      }));
      await restore(page, saved.id);
      assert.equal(await page.locator('#result-content').isVisible(), false, 'Missing record must clear cached results');
      assert.equal(await page.locator('#report-action').isVisible(), false);
      assert.match(await page.locator('#error-panel').innerText(), /Saved evaluation is no longer available/);
    });

    await check('review loading failure cannot enable a cached unreviewed form', async ({ page, saved }) => {
      await page.route(`**/api/v1/underwriting/evaluations/${saved.id}/review`, route => route.fulfill({
        status: 503, contentType: 'application/json', body: JSON.stringify({ detail: 'Review service temporarily unavailable' })
      }));
      await restore(page, saved.id);
      assert.equal(await page.locator('#human-review-panel').isVisible(), false);
      assert.equal(await page.locator('#report-action').isVisible(), false);
      assert.match(await page.locator('#error-panel').innerText(), /Review service temporarily unavailable/);
    });

    await check('fresh history remains reviewable and scenario navigation clears it', async ({ page, context, saved }) => {
      await restore(page, saved.id);
      assert.equal(await reportId(page), saved.id);
      assert.equal(await page.locator('#review-form').isVisible(), true);
      await page.locator('#review-comment').fill('历史案件当前复核通过。');
      await page.locator('#review-outcome').selectOption('APPROVED');
      await page.locator('#submit-review').click();
      await page.locator('#review-result').waitFor({ state: 'visible' });
      const report = await context.request.get(`${evaluationApi}/${saved.id}/report`);
      assert.match(await report.text(), /历史案件当前复核通过/);
      await page.locator('#scenario-list button').nth(1).click();
      await page.locator('#scenario-detail').waitFor({ state: 'visible' });
      assert.equal(await page.locator('#result-content').isVisible(), false);
      assert.equal(await page.locator('#download-report').getAttribute('href'), null);
    });
  } finally {
    await browser.close();
  }
  console.log(JSON.stringify({ pass: !failed, checks: results }, null, 2));
  process.exitCode = failed ? 1 : 0;
})().catch(error => { console.error(error); process.exitCode = 1; });
