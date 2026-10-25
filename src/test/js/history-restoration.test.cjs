const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const client = fs.readFileSync(process.env.DEMO_CLIENT_PATH || path.join(__dirname, '../../main/resources/static/demo/app.js'), 'utf8');
const api = '/api/v1/underwriting/evaluations';
const savedEvaluation = id => ({
  id, policyNo: 'P-1001', decision: 'MANUAL_REVIEW', riskLevel: 'HIGH', riskScore: 70,
  summary: '服务端保存的原评估', stepTraces: [], reasons: [], recommendedActions: []
});
const response = (body, status = 200) => ({ ok: status >= 200 && status < 300, status, json: async () => body });
const noReview = () => response({ errorCode: 'HUMAN_REVIEW_NOT_FOUND' }, 404);
const deferred = () => {
  let resolve;
  const promise = new Promise(done => { resolve = done; });
  return { promise, resolve };
};

// Minimal browser boundary, not a second implementation of restoration. Execute
// the complete production asset and assert its state, DOM output and HTTP inputs.
function harness(fetchSaved) {
  const nodes = new Map();
  const node = () => ({
    hidden: false, value: '', textContent: '', dataset: {}, style: {}, children: [], attributes: {},
    append(...children) { this.children.push(...children); },
    replaceChildren(...children) { this.children = children; },
    setAttribute(name, value) { this.attributes[name] = value; },
    removeAttribute(name) { delete this.attributes[name]; },
    addEventListener() {}, reset() {}, scrollIntoView() {}, focus() {}
  });
  const find = selector => {
    if (!nodes.has(selector)) nodes.set(selector, node());
    return nodes.get(selector);
  };
  const requests = [];
  const storage = { getItem: () => null, setItem() {} };
  const sandbox = vm.createContext({
    document: { querySelector: find, querySelectorAll: () => [], createElement: node, documentElement: node(), addEventListener() {} },
    window: { matchMedia: () => ({ matches: false }), clearTimeout() {}, setTimeout() {}, clearInterval() {}, setInterval: () => 1 },
    localStorage: storage, sessionStorage: storage, AbortController, URLSearchParams, performance,
    fetch(url, options) {
      // Initialization stays pending, isolating each explicit user action.
      if (!url.startsWith(api) && !url.startsWith('/api/v1/demo/scenarios/')) return new Promise(() => {});
      requests.push({ url, ...options });
      return fetchSaved(url, options);
    }
  });
  vm.runInContext(client, sandbox);
  const state = vm.runInContext('state', sandbox);
  const restore = vm.runInContext('restoreHistory', sandbox);
  function seed(...ids) {
    state.history = ids.map(id => ({
      id, policyNo: 'P-1001', durationMs: 12, review: null,
      evaluation: { ...savedEvaluation(id), summary: '过期浏览器快照' }
    }));
    state.selectedPolicyNo = 'P-1001';
    state.detail = { policyNo: 'P-1001', expectedResult: { decision: 'MANUAL_REVIEW' } };
    find('#question-input').value = '运行核保';
  }
  return { state, find, requests, seed, restore,
    runEvaluation: vm.runInContext('runEvaluation', sandbox),
    selectScenario: vm.runInContext('selectScenario', sandbox),
    submitHumanReview: vm.runInContext('submitHumanReview', sandbox),
    runDecisionReplay: vm.runInContext('runDecisionReplay', sandbox) };
}
const contents = node => [node.textContent, ...node.children.map(contents)].join(' ');

test('history reloads the canonical evaluation and cross-client immutable review without POST', async () => {
  const review = { id: 'REV-1', evaluationId: 'old', reviewerId: 'UW-OTHER', outcome: 'APPROVED', comment: '另一客户端已复核' };
  const app = harness(url => Promise.resolve(url.endsWith('/review') ? response(review) : response(savedEvaluation('old'))));
  app.seed('old');
  await app.restore('old');
  assert.equal(app.state.evaluation.summary, '服务端保存的原评估');
  assert.equal(app.state.review, review);
  assert.equal(app.find('#review-form').hidden, true);
  assert.match(contents(app.find('#review-result')), /UW-OTHER/);
  assert.equal(app.find('#download-report').href, `${api}/old/report`);
  assert.deepEqual(app.requests.map(item => [item.url, item.method || 'GET']), [[`${api}/old`, 'GET'], [`${api}/old/review`, 'GET']]);
});

test('history cancels an active evaluation and a late response cannot replace the selected case', async () => {
  const late = deferred();
  let evaluationSignal;
  const app = harness((url, options) => {
    if (options.method === 'POST') { evaluationSignal = options.signal; return late.promise; }
    return Promise.resolve(url.endsWith('/review') ? noReview() : response(savedEvaluation('old')));
  });
  app.seed('old');
  const running = app.runEvaluation();
  await app.restore('old');
  late.resolve(response(savedEvaluation('late')));
  await running;
  assert.equal(evaluationSignal.aborted, true);
  assert.equal(app.state.evaluation.id, 'old');
  assert.equal(app.find('#download-report').href, `${api}/old/report`);
  assert.equal(app.find('#run-evaluation').textContent, '▶ 运行智能核保');
});

test('a newer history selection survives an older response even when transport ignores abort', async () => {
  const oldResponse = deferred();
  const app = harness(url => {
    if (url === `${api}/old`) return oldResponse.promise;
    return Promise.resolve(url.endsWith('/review') ? noReview() : response(savedEvaluation('new')));
  });
  app.seed('old', 'new');
  const oldOpen = app.restore('old');
  await app.restore('new');
  oldResponse.resolve(response(savedEvaluation('old')));
  await oldOpen;
  assert.equal(app.state.evaluation.id, 'new');
  assert.equal(app.find('#download-report').href, `${api}/new/report`);
});

for (const failure of ['missing evaluation', 'unavailable review']) {
  test(`${failure} leaves no cached editable result or report link`, async () => {
    const app = harness(url => Promise.resolve(
      url.endsWith('/review')
        ? failure === 'unavailable review' ? response({ detail: 'Review unavailable' }, 503) : noReview()
        : failure === 'missing evaluation' ? response({ errorCode: 'EVALUATION_NOT_FOUND', detail: 'Missing saved evaluation' }, 404) : response(savedEvaluation('old'))
    ));
    app.seed('old');
    // Begin with a visible old result to prove it is cleared on navigation.
    app.state.evaluation = savedEvaluation('previous');
    app.find('#download-report').attributes.href = `${api}/previous/report`;
    await app.restore('old');
    assert.equal(app.state.evaluation, null);
    assert.equal(app.find('#result-content').hidden, true);
    assert.equal(app.find('#human-review-panel').hidden, true);
    assert.equal(app.find('#report-action').hidden, true);
    assert.equal(app.find('#download-report').attributes.href, undefined);
    assert.match(app.find('#error-panel').textContent, failure === 'missing evaluation' ? /Missing saved evaluation/ : /Review unavailable/);
  });
}

test('an unreviewed saved case remains writable after its review404 and unknown history IDs do nothing', async () => {
  const app = harness(url => Promise.resolve(url.endsWith('/review') ? noReview() : response(savedEvaluation('old'))));
  app.seed('old');
  await app.restore('unknown');
  assert.equal(app.requests.length, 0);
  await app.restore('old');
  assert.equal(app.state.evaluation.id, 'old');
  assert.equal(app.state.review, null);
  assert.equal(app.find('#review-form').hidden, false);
  assert.equal(app.find('#human-review-panel').hidden, false);
});


test('a canceled evaluation failure cannot overwrite the restored case status or error panel', async () => {
  const late = deferred();
  const app = harness((url, options) => options.method === 'POST' ? late.promise
    : Promise.resolve(url.endsWith('/review') ? noReview() : response(savedEvaluation('old'))));
  app.seed('old');
  const running = app.runEvaluation();
  await app.restore('old');
  const status = app.find('#evaluation-status').textContent;
  late.resolve(response({ detail: 'Old evaluation failed' }, 503));
  await running;
  assert.equal(app.find('#evaluation-status').textContent, status);
  assert.equal(app.find('#error-panel').hidden, true);
});

test('a canceled review failure cannot change a newer pending review or enable its button', async () => {
  const oldReview = deferred();
  const newReview = deferred();
  const app = harness((url, options) => options.method === 'POST'
    ? url.includes('/old/') ? oldReview.promise : newReview.promise
    : Promise.resolve(url.endsWith('/review') ? noReview() : response(savedEvaluation(url.split('/').at(-1)))));
  app.seed('old', 'new');
  await app.restore('old');
  const first = app.submitHumanReview({ preventDefault() {} });
  await app.restore('new');
  const second = app.submitHumanReview({ preventDefault() {} });
  const status = app.find('#review-status').textContent;
  oldReview.resolve(response({ detail: 'Old review failed' }, 503));
  await first;
  assert.equal(app.find('#review-status').textContent, status);
  assert.equal(app.find('#submit-review').disabled, true);
  assert.equal(app.find('#review-form').attributes['aria-busy'], 'true');
  newReview.resolve(response({ id: 'REV-NEW', outcome: 'APPROVED', comment: 'New review saved' }));
  await second;
  assert.equal(app.state.review.id, 'REV-NEW');
});

test('a canceled scenario failure cannot overwrite restored case guidance or show an old error', async () => {
  const late = deferred();
  const app = harness(url => url.startsWith('/api/v1/demo/scenarios/') ? late.promise
    : Promise.resolve(url.endsWith('/review') ? noReview() : response(savedEvaluation('old'))));
  app.seed('old');
  const selecting = app.selectScenario('P-2001');
  await app.restore('old');
  const guidance = app.find('#detail-status').textContent;
  late.resolve(response({ detail: 'Old scenario failed' }, 503));
  await selecting;
  assert.equal(app.find('#detail-status').textContent, guidance);
  assert.equal(app.find('#error-panel').hidden, true);
});

test('a canceled replay failure cannot overwrite a newly restored case', async () => {
  const late = deferred();
  const app = harness((url, options) => options.method === 'POST' ? late.promise
    : Promise.resolve(url.endsWith('/review') ? noReview() : response(savedEvaluation(url.split('/').at(-1)))));
  app.seed('old', 'new');
  await app.restore('old');
  const replaying = app.runDecisionReplay();
  await app.restore('new');
  const status = app.find('#decision-replay-status').textContent;
  late.resolve(response({ detail: 'Old replay failed' }, 503));
  await replaying;
  assert.equal(app.state.evaluation.id, 'new');
  assert.equal(app.find('#decision-replay-status').textContent, status);
  assert.equal(app.find('#error-panel').hidden, true);
});

test('a current evaluation failure still reports its error and releases the run button', async () => {
  const app = harness(() => Promise.resolve(response({ detail: 'Current evaluation failed' }, 503)));
  app.seed('old');
  await app.runEvaluation();
  assert.match(app.find('#evaluation-status').textContent, /核保运行失败/);
  assert.match(app.find('#error-panel').textContent, /Current evaluation failed/);
  assert.equal(app.find('#run-evaluation').disabled, false);
});
