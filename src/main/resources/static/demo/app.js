"use strict";

const SCENARIO_API = "/api/v1/demo/scenarios";
const EVALUATION_API = "/api/v1/underwriting/evaluations";
const WORKLIST_API = "/api/v1/underwriting/review-worklist";
const HEALTH_API = "/actuator/health";
const HISTORY_KEY = "underwriting-demo-history-v1";
const THEME_KEY = "underwriting-demo-theme";
const MAX_HISTORY_ITEMS = 8;

const state = {
  scenarios: [],
  selectedPolicyNo: null,
  detail: null,
  evaluation: null,
  review: null,
  detailController: null,
  evaluationController: null,
  reviewController: null,
  replayController: null,
  comparisonController: null,
  worklistController: null,
  savedEvaluationController: null,
  worklistPage: null,
  comparisonResults: [],
  scenarioFilter: "ALL",
  evaluationStartedAt: 0,
  lastDurationMs: 0,
  pipelineTimer: null,
  history: [],
  toastTimer: null
};

const LABELS = {
  decisions: {
    APPROVE: "自动通过",
    MANUAL_REVIEW: "人工复核",
    REJECT: "拒保"
  },
  risks: {
    LOW: "低风险",
    MEDIUM: "中风险",
    HIGH: "高风险",
    CRITICAL: "极高风险"
  },
  severities: {
    INFO: "提示",
    WARNING: "警告",
    HIGH: "高风险",
    CRITICAL: "严重"
  },
  statuses: {
    SUCCESS: "成功",
    DEGRADED: "降级完成",
    FAILED: "失败",
    SKIPPED: "跳过"
  },
  hazards: {
    UNKNOWN: "未知",
    LOW: "低",
    MEDIUM: "中",
    HIGH: "高",
    RED: "红色预警",
    EXTREME: "极端"
  },
  fireProtection: {
    GOOD: "良好",
    ADEQUATE: "基本满足",
    INADEQUATE: "不满足",
    CRITICAL_DEFECT: "存在重大缺陷"
  },
  occupancies: {
    OFFICE: "办公楼",
    WAREHOUSE: "仓库",
    FACTORY: "制造厂房"
  },
  products: {
    PROPERTY: "企业财产险"
  },
  documents: {
    PRODUCT_CLAUSE: "保险条款",
    UNDERWRITING_RULE: "核保规则",
    RISK_GUIDE: "风险指引",
    HISTORICAL_CASE: "历史案例"
  },
  retrievalModes: {
    HYBRID: "混合检索",
    VECTOR_ONLY: "仅向量",
    LEXICAL_ONLY: "仅词法",
    UNKNOWN: "历史记录不可用"
  },
  steps: {
    QUESTION_UNDERSTANDING: "理解核保问题",
    BUSINESS_DATA_COLLECTION: "采集五类业务事实",
    KNOWLEDGE_RETRIEVAL: "检索核保知识",
    RISK_ANALYSIS: "分析综合风险",
    RULE_VALIDATION: "执行确定性规则",
    RECOMMENDATION_GENERATION: "生成核保建议",
    RESULT_PERSISTENCE: "保存可审计结果"
  },
  tools: {
    GET_POLICY: "保单信息工具",
    GET_QUOTATION: "报价信息工具",
    GET_UNDERWRITING_HISTORY: "历史核保工具",
    GET_SURVEY_REPORT: "风险查勘工具",
    GET_DISASTER_RISK: "灾害风险工具",
    VALIDATE_RULES: "规则校验工具"
  },
  worklistStatuses: {
    UNREVIEWED: "尚未复核",
    INFORMATION_REQUESTED: "已要求补充资料",
    REVIEWED: "已完成复核"
  },
  reviewOutcomes: {
    APPROVED: "同意承保",
    REJECTED: "拒绝承保",
    MORE_INFORMATION_REQUIRED: "要求补充资料"
  },
  reviewRelationships: {
    CONFIRMED: "确认 Agent 建议",
    OVERRIDDEN: "推翻 Agent 建议",
    RESOLVED_MANUAL_REVIEW: "完成人工复核",
    CONTINUED_MANUAL_REVIEW: "继续补充资料"
  },
  driftSeverities: {
    STABLE: "稳定",
    SOURCE_DRIFT: "来源变化",
    DECISION_DRIFT: "决策变化"
  },
  decisionChanges: {
    UNCHANGED: "结论不变",
    STRENGTHENED: "结论趋严",
    WEAKENED: "结论趋松"
  }
};

const FACT_GROUPS = [
  ["保单信息", detail => [
    ["保单号", detail.policy?.policyNo],
    ["险种", label("products", detail.policy?.productCode)],
    ["被保险人", detail.policy?.insuredName],
    ["行业 / 用途", label("occupancies", detail.policy?.occupancy)],
    ["风险地址", detail.policy?.address],
    ["保险期间", `${text(detail.policy?.startDate)} 至 ${text(detail.policy?.endDate)}`]
  ]],
  ["报价信息", detail => [
    ["保险金额", detail.sumInsuredDisplay],
    ["费率", formatRate(detail.quotation?.rate)],
    ["保费", detail.premiumDisplay],
    ["免赔额", detail.deductibleDisplay]
  ]],
  ["历史记录", detail => [
    ["三年出险次数", `${text(detail.history?.claimCountThreeYears, "0")} 次`],
    ["三年已赔金额", detail.paidLossThreeYearsDisplay],
    ["历史结论", joinValues(detail.history?.priorDecisions, "无历史核保记录")]
  ]],
  ["风险查勘", detail => [
    ["消防状态", label("fireProtection", detail.survey?.fireProtectionStatus)],
    ["排水整改", detail.survey?.drainageRemediationCompleted ? "已完成" : "未完成"],
    ["未决问题", joinValues(detail.survey?.openIssues, "无")],
    ["查勘结论", detail.survey?.conclusion]
  ]],
  ["灾害风险", detail => [
    ["风险区域", detail.disaster?.riskZone],
    ["暴雨", label("hazards", detail.disaster?.rainstorm)],
    ["洪水", label("hazards", detail.disaster?.flood)],
    ["火灾", label("hazards", detail.disaster?.fire)],
    ["数据日期", detail.disaster?.dataDate]
  ]]
];

const PIPELINE_STEPS = [
  "QUESTION_UNDERSTANDING",
  "BUSINESS_DATA_COLLECTION",
  "KNOWLEDGE_RETRIEVAL",
  "RISK_ANALYSIS",
  "RULE_VALIDATION",
  "RECOMMENDATION_GENERATION",
  "RESULT_PERSISTENCE"
];

function find(selector) {
  return document.querySelector(selector);
}

function text(value, fallback = "暂无") {
  return value === null || value === undefined || value === "" ? fallback : String(value);
}

function label(group, value) {
  return LABELS[group]?.[value] || text(value);
}

function joinValues(values, fallback = "暂无") {
  return Array.isArray(values) && values.length ? values.join("；") : fallback;
}

function formatRate(value) {
  const rate = Number(value);
  return Number.isFinite(rate) ? `${(rate * 100).toFixed(2)}%` : "暂无";
}

function clampScore(value) {
  const score = Number(value);
  return Number.isFinite(score) ? Math.min(100, Math.max(0, score)) : 0;
}

function el(tagName, className, content) {
  const node = document.createElement(tagName);
  if (className) node.className = className;
  if (content !== undefined) node.textContent = text(content);
  return node;
}

function setChildren(target, children) {
  target.replaceChildren(...children.filter(Boolean));
}

function storageRead(storage, key, fallback) {
  try {
    const value = storage.getItem(key);
    return value ? JSON.parse(value) : fallback;
  } catch (error) {
    return fallback;
  }
}

function storageWrite(storage, key, value) {
  try {
    storage.setItem(key, JSON.stringify(value));
  } catch (error) {
    // Storage can be unavailable in private browsing; the workbench still functions in memory.
  }
}

function showToast(message) {
  const toast = find("#toast");
  window.clearTimeout(state.toastTimer);
  toast.textContent = text(message);
  toast.hidden = false;
  state.toastTimer = window.setTimeout(() => {
    toast.hidden = true;
  }, 2800);
}

function preferredTheme() {
  const stored = storageRead(localStorage, THEME_KEY, null);
  if (stored === "dark" || stored === "light") return stored;
  return window.matchMedia?.("(prefers-color-scheme: dark)").matches ? "dark" : "light";
}

function applyTheme(theme) {
  const isDark = theme === "dark";
  document.documentElement.dataset.theme = isDark ? "dark" : "light";
  const button = find("#theme-toggle");
  button.setAttribute("aria-pressed", String(isDark));
  button.title = isDark ? "切换浅色模式" : "切换深色模式";
  find("#theme-icon").textContent = isDark ? "☀" : "◐";
}

function toggleTheme() {
  const theme = document.documentElement.dataset.theme === "dark" ? "light" : "dark";
  applyTheme(theme);
  storageWrite(localStorage, THEME_KEY, theme);
}

async function checkServiceHealth() {
  const status = find("#service-status");
  const statusText = find("#service-status-text");
  const startedAt = performance.now();
  try {
    const health = await requestJson(HEALTH_API);
    const latency = Math.max(0, Math.round(performance.now() - startedAt));
    const isUp = health?.status === "UP";
    status.dataset.status = isUp ? "UP" : "DOWN";
    statusText.textContent = isUp ? `服务正常 · ${latency} ms` : "服务状态异常";
    status.title = statusText.textContent;
  } catch (error) {
    status.dataset.status = "DOWN";
    statusText.textContent = "服务不可用";
    status.title = text(error.message);
  }
}

async function requestJson(url, options = {}) {
  const { headers = {}, ...requestOptions } = options;
  const response = await fetch(url, {
    ...requestOptions,
    headers: { Accept: "application/json", ...headers }
  });

  let payload = null;
  try {
    payload = await response.json();
  } catch (error) {
    if (response.ok) {
      throw new Error("服务返回了无法识别的数据格式。");
    }
  }

  if (!response.ok) {
    const message = payload?.detail || payload?.title || `请求失败（HTTP ${response.status}）`;
    const trace = payload?.traceId ? `，追踪编号：${payload.traceId}` : "";
    const error = new Error(`${message}${trace}`);
    error.status = response.status;
    error.code = payload?.errorCode;
    throw error;
  }
  return payload;
}

function renderWorklistItem(item) {
  const row = el("li", "worklist-item");
  row.dataset.evaluationId = item.id;
  const details = el("div", "worklist-item__details");
  details.append(
    el("strong", null, `${text(item.policyNo)} · ${label("decisions", item.decision)} · ${item.riskScore}/100`),
    el("span", null, `${label("worklistStatuses", item.status)} · ${new Date(item.createdAt).toLocaleString("zh-CN")}`),
    el("small", "worklist-item__id", item.id)
  );
  if (item.reviewOutcome) {
    details.append(el("span", null, `${label("reviewOutcomes", item.reviewOutcome)} · ${text(item.reviewerId)}`));
  }
  const button = el("button", "text-button", "打开案件");
  button.type = "button";
  button.setAttribute("aria-label", `打开评估 ${item.id}`);
  button.addEventListener("click", () => openSavedEvaluation(item.id));
  row.append(details, button);
  return row;
}

async function loadReviewWorklist(page = 0) {
  state.worklistController?.abort();
  const controller = new AbortController();
  state.worklistController = controller;
  state.worklistPage = null;
  const params = new URLSearchParams({ page: String(page), size: "10" });
  for (const [parameter, selector] of [
    ["status", "#worklist-status-filter"],
    ["decision", "#worklist-decision-filter"],
    ["policyNo", "#worklist-policy-filter"]
  ]) {
    const value = find(selector).value.trim();
    if (value) params.set(parameter, value);
  }
  find("#worklist-list").replaceChildren();
  find("#worklist-list").setAttribute("aria-busy", "true");
  find("#worklist-status").textContent = "正在读取服务端案件……";
  find("#worklist-page").textContent = "正在读取……";
  find("#worklist-previous").disabled = true;
  find("#worklist-next").disabled = true;
  try {
    const result = await requestJson(`${WORKLIST_API}?${params}`, { signal: controller.signal });
    if (state.worklistController !== controller) return;
    if (page > 0 && result.items.length === 0 && result.totalElements > 0) {
      loadReviewWorklist(Math.max(0, result.totalPages - 1));
      return;
    }
    state.worklistPage = result;
    setChildren(find("#worklist-list"), result.items.map(renderWorklistItem));
    find("#worklist-status").textContent = result.totalElements
      ? "已读取服务端保存的评估。选择案件查看详情或记录人工复核。"
      : "没有符合筛选条件的已保存评估。可先运行一组场景，或调整筛选条件。";
    find("#worklist-page").textContent = result.totalPages
      ? `共 ${result.totalElements} 条 · 第 ${result.page + 1} / ${result.totalPages} 页`
      : "共 0 条";
    find("#worklist-previous").disabled = result.page <= 0;
    find("#worklist-next").disabled = result.page + 1 >= result.totalPages;
  } catch (error) {
    if (state.worklistController === controller && error.name !== "AbortError") {
      find("#worklist-status").textContent = `案件列表读取失败：${text(error.message)} 请点击查询 / 刷新重试。`;
      find("#worklist-page").textContent = "读取失败";
    }
  } finally {
    if (state.worklistController === controller) {
      state.worklistController = null;
      find("#worklist-list").setAttribute("aria-busy", "false");
    }
  }
}

async function openSavedEvaluation(evaluationId) {
  state.savedEvaluationController?.abort();
  state.detailController?.abort();
  state.detailController = null;
  state.evaluationController?.abort();
  state.evaluationController = null;
  resetEvaluation();
  state.detail = null;
  find("#scenario-detail").hidden = true;
  setDetailLoading(false);
  const controller = new AbortController();
  state.savedEvaluationController = controller;
  find("#worklist-open-status").textContent = `正在读取评估 ${evaluationId}……`;
  find("#evaluation-status").textContent = "正在读取服务端已保存评估……";
  const url = `${EVALUATION_API}/${encodeURIComponent(evaluationId)}`;
  try {
    const [evaluation, review] = await Promise.all([
      requestJson(url, { signal: controller.signal }),
      requestJson(`${url}/review`, { signal: controller.signal }).catch(error => {
        if (error.status === 404 && error.code === "HUMAN_REVIEW_NOT_FOUND") return null;
        throw error;
      })
    ]);
    if (state.savedEvaluationController !== controller) return;
    let detail = null;
    if (state.scenarios.some(scenario => scenario.policyNo === evaluation.policyNo)) {
      try {
        detail = await requestJson(`${SCENARIO_API}/${encodeURIComponent(evaluation.policyNo)}`, {
          signal: controller.signal
        });
      } catch (error) {
        if (error.name === "AbortError") throw error;
      }
    }
    if (state.savedEvaluationController !== controller) return;
    state.selectedPolicyNo = evaluation.policyNo;
    state.detail = detail;
    renderScenarioButtons();
    if (detail) renderScenarioDetail(detail);
    find("#detail-status").hidden = false;
    find("#detail-status").textContent = detail
      ? "上方为当前演示场景事实；下方读取已保存的原始评估。"
      : "当前演示目录无可用场景事实；下方仍可查看并复核已保存的评估。";
    state.evaluation = evaluation;
    state.lastDurationMs = (evaluation.stepTraces || []).reduce((total, step) => total + (step.durationMs || 0), 0);
    renderEvaluation(evaluation, detail?.expectedResult, state.lastDurationMs, { record: false, review });
    activateResultTab("review", true);
    find("#evaluation-status").textContent = `已读取服务端评估：${evaluation.id}`;
    find("#worklist-open-status").textContent = `已打开评估 ${evaluation.id}，未重新执行 Agent。`;
  } catch (error) {
    if (state.savedEvaluationController === controller && error.name !== "AbortError") {
      find("#worklist-open-status").textContent = `案件读取失败：${text(error.message)}`;
      find("#evaluation-status").textContent = "已保存评估读取失败，请重试。";
      showError(error.message);
    }
  } finally {
    if (state.savedEvaluationController === controller) state.savedEvaluationController = null;
  }
}

function createScenarioButton(scenario) {
  const button = el("button", "scenario-card");
  button.type = "button";
  button.dataset.decision = text(scenario.expectedResult?.decision, "UNKNOWN");
  button.setAttribute("aria-pressed", String(scenario.policyNo === state.selectedPolicyNo));
  button.append(
    el("span", "scenario-card__code", scenario.policyNo),
    el("strong", "scenario-card__title", scenario.name),
    el(
      "span",
      "scenario-card__decision",
      `预期：${text(scenario.expectedResult?.decisionLabel, label("decisions", scenario.expectedResult?.decision))}`
    ),
    el("span", "scenario-card__summary", scenario.summary)
  );
  button.addEventListener("click", () => selectScenario(scenario.policyNo));
  return button;
}

function renderScenarioButtons() {
  const visibleScenarios = state.scenarioFilter === "ALL"
    ? state.scenarios
    : state.scenarios.filter(scenario => scenario.expectedResult?.decision === state.scenarioFilter);
  const buttons = visibleScenarios.map(createScenarioButton);
  setChildren(find("#scenario-list"), buttons);
  find("#scenario-status").textContent = state.scenarioFilter === "ALL"
    ? `已载入 ${buttons.length} 组虚构场景。`
    : `筛选出 ${buttons.length} 组${label("decisions", state.scenarioFilter)}场景。`;
}

function setScenarioFilter(filter) {
  state.scenarioFilter = filter;
  document.querySelectorAll("#scenario-filters [data-filter]").forEach(button => {
    button.setAttribute("aria-pressed", String(button.dataset.filter === filter));
  });
  renderScenarioButtons();
}

function createFactGroup(title, entries) {
  const group = el("section", "fact-group");
  const list = el("dl", "fact-list");
  for (const [term, value] of entries) {
    list.append(el("dt", null, term), el("dd", null, value));
  }
  group.append(el("h4", null, title), list);
  return group;
}

function renderScenarioDetail(detail) {
  find("#scenario-title").textContent = text(detail.name);
  find("#scenario-summary").textContent = text(detail.summary);
  find("#question-input").value = text(detail.question, "");
  updateQuestionCount();

  const expected = find("#expected-decision");
  expected.className = "decision-badge";
  expected.dataset.decision = text(detail.expectedResult?.decision, "UNKNOWN");
  expected.textContent = `预期结论：${text(
    detail.expectedResult?.decisionLabel,
    label("decisions", detail.expectedResult?.decision)
  )}`;

  const learningPoints = Array.isArray(detail.learningPoints) ? detail.learningPoints : [];
  setChildren(find("#learning-points"), learningPoints.map(point => el("li", null, point)));
  setChildren(
    find("#facts-grid"),
    FACT_GROUPS.map(([title, readEntries]) => createFactGroup(title, readEntries(detail)))
  );

  find("#scenario-detail").hidden = false;
  find("#detail-status").hidden = true;
  find("#run-evaluation").disabled = false;
}

function updateQuestionCount() {
  const input = find("#question-input");
  find("#question-count").textContent = `${input.value.length} / 500`;
  find("#run-evaluation").disabled = !state.detail || !input.value.trim() || Boolean(state.evaluationController);
}

function resetQuestion() {
  if (!state.detail) return;
  find("#question-input").value = text(state.detail.question, "");
  updateQuestionCount();
  showToast("已恢复当前场景的默认核保问题");
}

function renderDecisionSummary(evaluation, expected) {
  const wrapper = el("section", "decision-overview");
  const heading = el("div", "decision-overview__heading");
  const badge = el("p", "decision-badge", label("decisions", evaluation.decision));
  badge.dataset.decision = text(evaluation.decision, "UNKNOWN");
  const score = clampScore(evaluation.riskScore);
  const risk = el("div", "risk-score");
  risk.append(
    el("span", "risk-score__number", score),
    el("span", "risk-score__unit", "/ 100 风险分")
  );
  heading.append(badge, risk);

  const matches = evaluation.decision === expected?.decision;
  const safetyOverride = !matches && Array.isArray(evaluation.degradations) && evaluation.degradations.length > 0;
  const comparison = el(
    "p",
    matches
      ? "comparison comparison--match"
      : safetyOverride
        ? "comparison comparison--safety"
        : "comparison comparison--different",
    !expected
      ? "已保存的服务端评估；当前目录无场景预期可供对照"
      : matches
      ? "实际结论与场景预期一致"
      : safetyOverride
        ? `安全降级已覆盖常规场景预期（常规预期：${text(expected?.decisionLabel, label("decisions", expected?.decision))}）`
      : `实际结论与预期不同（预期：${text(expected?.decisionLabel, label("decisions", expected?.decision))}）`
  );

  const bar = el("div", "risk-progress");
  bar.setAttribute("role", "progressbar");
  bar.setAttribute("aria-label", "核保风险分数");
  bar.setAttribute("aria-valuemin", "0");
  bar.setAttribute("aria-valuemax", "100");
  bar.setAttribute("aria-valuenow", String(score));
  const fill = el("span", "risk-progress__fill");
  fill.style.width = `${score}%`;
  bar.append(fill);

  if (!expected) comparison.className = "comparison";
  wrapper.append(
    heading,
    comparison,
    el("p", "risk-label", `风险等级：${label("risks", evaluation.riskLevel)}`),
    bar,
    el("p", "model-summary", evaluation.summary)
  );
  setChildren(find("#decision-summary"), [wrapper]);
}

function renderTextList(targetId, title, items = []) {
  const section = el("section", "result-section");
  const list = el("ul", "result-list");
  const values = Array.isArray(items) && items.length ? items : ["暂无"];
  list.append(...values.map(item => el("li", null, item)));
  section.append(el("h3", null, title), list);
  setChildren(find(`#${targetId}`), [section]);
}

function renderDegradations(items) {
  const target = find("#degradation-list");
  if (!items.length) {
    target.replaceChildren();
    return;
  }
  const section = el("section", "result-section degradation-section");
  const notices = items.map(item => {
    const notice = el("article", "degradation-notice");
    notice.append(
      el("p", "degradation-notice__code", text(item.code, "DATA_SOURCE_DEGRADED")),
      el("h3", null, `${label("tools", item.toolName)}已安全降级`),
      el("p", null, item.message),
      el(
        "p",
        "degradation-notice__meta",
        `工具错误码：${text(item.errorCode)} · 决策下限：${label("decisions", item.decisionFloor)}`
      )
    );
    return notice;
  });
  section.append(el("h3", null, "数据质量与安全降级"), ...notices);
  setChildren(target, [section]);
}

function renderRuleHits(items) {
  const section = el("section", "result-section");
  const grid = el("div", "card-grid");
  const cards = items.length ? items.map(item => {
    const card = el("article", "result-card");
    const impact = Number(item.scoreImpact);
    card.append(
      el("p", "result-card__meta", `${text(item.code)} · ${label("severities", item.severity)}`),
      el("h4", null, label("decisions", item.decision)),
      el("p", null, item.reason),
      el(
        "p",
        "score-impact",
        `风险分影响：${Number.isFinite(impact) && impact >= 0 ? "+" : ""}${text(item.scoreImpact, "0")}`
      )
    );
    return card;
  }) : [el("p", "empty-state", "本场景没有规则命中，基础风险分决定最终结果。")];
  grid.append(...cards);
  section.append(el("h3", null, "规则命中"), grid);
  setChildren(find("#rule-hit-list"), [section]);
}

function renderEvidence(items) {
  const section = el("section", "result-section");
  const grid = el("div", "card-grid");
  const cards = items.length ? items.map(item => {
    const card = el("article", "result-card");
    const relevance = Math.round(clampScore(Number(item.score) * 100));
    const vectorScore = Math.round(clampScore(Number(item.vectorScore) * 100));
    const lexicalScore = Math.round(clampScore(Number(item.lexicalScore) * 100));
    const version = Number(item.knowledgeVersion) > 0 ? `v${item.knowledgeVersion}` : "版本未知";
    const matchedTerms = Array.isArray(item.matchedTerms) && item.matchedTerms.length
      ? item.matchedTerms.join("、")
      : "无词法命中词";
    card.append(
      el(
        "p",
        "result-card__meta",
        `${label("documents", item.type)} · ${version} · ${label("retrievalModes", item.retrievalMode)}`
      ),
      el("h4", null, item.title),
      el("p", null, item.excerpt),
      el("p", "result-card__meta", `综合 ${relevance}% · 向量 ${vectorScore}% · 词法 ${lexicalScore}%`),
      el("p", "result-card__meta", `命中词：${matchedTerms}`)
    );
    return card;
  }) : [el("p", "empty-state", "本场景没有返回知识证据。")];
  grid.append(...cards);
  section.append(el("h3", null, "知识证据"), grid);
  setChildren(find("#evidence-list"), [section]);
}

function renderSourceSnapshot(evaluation) {
  const section = el("section", "result-section");
  const grid = el("div", "trace-grid");
  const modelResponse = evaluation?.modelResponse;
  const evidenceItems = Array.isArray(evaluation?.evidence) ? evaluation.evidence : [];
  const ruleSet = evaluation?.ruleSet;
  const prompt = modelResponse?.prompt;
  const promptVersion = Number(prompt?.version) > 0 ? `v${prompt.version}` : "历史记录不可用";
  const fingerprint = prompt?.templateSha256 && prompt.templateSha256 !== "unavailable"
    ? `${prompt.templateSha256.slice(0, 16)}…`
    : "历史记录不可用";
  const knowledgeVersions = [...new Set(evidenceItems.map(item =>
    `${text(item.documentId)}@${Number(item.knowledgeVersion) > 0 ? `v${item.knowledgeVersion}` : "未知版本"}`
  ))];

  const promptCard = el("article", "trace-card");
  promptCard.append(
    el("h4", null, "Prompt 快照"),
    el("p", null, `模板：${text(prompt?.code, "历史记录不可用")} ${promptVersion}`),
    el("p", "result-card__meta", `SHA-256：${fingerprint}`)
  );

  const modelCard = el("article", "trace-card");
  modelCard.append(
    el("h4", null, "模型路由"),
    el("p", null, `${text(modelResponse?.provider)} / ${text(modelResponse?.model)}`),
    el(
      "p",
      "result-card__meta",
      `尝试 ${text(modelResponse?.attempts, "0")} 次 · ${modelResponse?.fallbackUsed ? "已显式降级" : "未降级"}`
    )
  );

  const knowledgeCard = el("article", "trace-card");
  knowledgeCard.append(
    el("h4", null, "知识版本引用"),
    el("p", null, knowledgeVersions.length ? knowledgeVersions.join("；") : "本次没有知识证据"),
    el("p", "result-card__meta", `${knowledgeVersions.length} 个文档版本`)
  );

  const ruleCard = el("article", "trace-card");
  const ruleFingerprint = ruleSet?.sha256 && ruleSet.sha256 !== "unavailable"
    ? `${ruleSet.sha256.slice(0, 16)}…`
    : "历史记录不可用";
  ruleCard.append(
    el("h4", null, "规则集快照"),
    el("p", null, text(ruleSet?.version, "历史记录不可用")),
    el("p", "result-card__meta", `${text(ruleSet?.ruleCount, "0")} 条规则 · SHA-256：${ruleFingerprint}`)
  );

  grid.append(promptCard, ruleCard, modelCard, knowledgeCard);
  section.append(el("h3", null, "决策来源快照"), grid);
  setChildren(find("#source-snapshot"), [section]);
}

function renderDecisionDrift(drift) {
  const target = find("#decision-drift-result");
  const summary = el("section", "drift-summary");
  summary.dataset.severity = text(drift?.severity, "STABLE");
  const heading = el("div", "drift-summary__heading");
  heading.append(
    el("span", "drift-badge", label("driftSeverities", drift?.severity)),
    el("strong", null, label("decisionChanges", drift?.decisionChange))
  );

  const scoreDelta = Number(drift?.riskScoreDelta) || 0;
  const metrics = el("div", "drift-metrics");
  [
    ["基线结论", label("decisions", drift?.baselineDecision)],
    ["重演结论", label("decisions", drift?.replayDecision)],
    ["风险分变化", `${scoreDelta > 0 ? "+" : ""}${scoreDelta}`],
    ["规则变化", `+${drift?.addedRuleCodes?.length || 0} / -${drift?.removedRuleCodes?.length || 0}`],
    ["证据变化", `+${drift?.addedEvidence?.length || 0} / -${drift?.removedEvidence?.length || 0}`]
  ].forEach(([name, value]) => {
    const item = el("div", "drift-metric");
    item.append(el("span", null, name), el("strong", null, value));
    metrics.append(item);
  });

  const explanations = el("ul", "drift-explanations");
  const explanationItems = Array.isArray(drift?.explanations) && drift.explanations.length
    ? drift.explanations
    : ["服务未返回漂移解释。"];
  explanations.append(...explanationItems.map(item => el("li", null, item)));

  const sourceFlags = [
    ["Prompt", drift?.promptChanged],
    ["规则集", drift?.ruleSetChanged],
    ["模型路由", drift?.modelChanged],
    ["证据评分", Boolean(drift?.evidenceScoreChanges?.length)]
  ];
  const sourceRow = el("div", "drift-source-flags");
  sourceRow.append(...sourceFlags.map(([name, changed]) => {
    const item = el("span", "drift-source-flag", `${name}：${changed ? "有变化" : "无变化"}`);
    item.dataset.changed = changed ? "true" : "false";
    return item;
  }));

  summary.append(heading, metrics, sourceRow, explanations);
  setChildren(target, [summary]);
  target.hidden = false;
  find("#decision-replay-status").textContent =
    `重演完成：${label("driftSeverities", drift?.severity)}，基线 ${text(drift?.baselineEvaluationId)} → 重演 ${text(drift?.replayEvaluationId)}。`;
}

function clearDecisionDrift() {
  find("#decision-replay-status").textContent = "尚未执行决策重演。";
  find("#decision-drift-result").hidden = true;
  find("#decision-drift-result").replaceChildren();
}

function renderStepTraces(items) {
  const section = el("section", "result-section");
  const list = el("ol", "step-timeline");
  const entries = items.map((item, index) => {
    const entry = el("li", "step-item");
    entry.dataset.status = text(item.status, "UNKNOWN");
    const content = [
      el("span", "step-index", index + 1),
      el("strong", null, label("steps", item.step)),
      el("span", "trace-status", `${label("statuses", item.status)} · ${text(item.durationMs, "0")} ms`),
      item.errorCode ? el("span", "error-code", `错误码：${item.errorCode}`) : null
    ];
    entry.append(...content.filter(Boolean));
    return entry;
  });
  list.append(...(entries.length ? entries : [el("li", "empty-state", "暂无步骤轨迹。")]));
  section.append(el("h3", null, "七步 Agent 执行轨迹"), list);
  setChildren(find("#step-timeline"), [section]);
}

function renderToolTraces(items) {
  const section = el("section", "result-section");
  const grid = el("div", "trace-grid");
  const cards = items.length ? items.map(item => {
    const card = el("article", "trace-card");
    const content = [
      el("h4", null, label("tools", item.toolName)),
      el("p", "result-card__meta", `${label("statuses", item.status)} · ${text(item.durationMs, "0")} ms`),
      el("p", null, `输入：${text(item.inputSummary)}`),
      el("p", null, `输出：${text(item.outputSummary)}`),
      item.errorCode ? el("p", "error-code", `错误码：${item.errorCode}`) : null
    ];
    card.append(...content.filter(Boolean));
    return card;
  }) : [el("p", "empty-state", "暂无工具调用记录。")];
  grid.append(...cards);
  section.append(el("h3", null, "工具调用记录"), grid);
  setChildren(find("#tool-trace-list"), [section]);
}

function renderResultMetrics(evaluation, durationMs) {
  const tools = Array.isArray(evaluation.toolTraces) ? evaluation.toolTraces : [];
  const successfulTools = tools.filter(item => item.status === "SUCCESS" || item.status === "DEGRADED").length;
  const model = evaluation.modelResponse;
  const metrics = [
    ["端到端耗时", `${Math.max(0, durationMs)} ms`],
    ["业务工具", `${successfulTools}/${tools.length} 可用`],
    ["规则命中", `${evaluation.ruleHits?.length || 0} 条`],
    ["知识证据", `${evaluation.evidence?.length || 0} 条`],
    ["模型路由", `${text(model?.provider)} / ${text(model?.model)}`]
  ];
  setChildren(find("#result-metrics"), metrics.map(([title, value]) => {
    const card = el("article", "result-metric");
    card.title = value;
    card.append(el("span", null, title), el("strong", null, value));
    return card;
  }));
}

function activateResultTab(tabName, focus = false) {
  document.querySelectorAll("#result-tabs [data-tab]").forEach(button => {
    const active = button.dataset.tab === tabName;
    button.setAttribute("aria-selected", String(active));
    button.tabIndex = active ? 0 : -1;
    if (active && focus) button.focus();
  });
  document.querySelectorAll("[data-result-panel]").forEach(panel => {
    panel.hidden = panel.dataset.resultPanel !== tabName;
  });
}

function handleTabKeydown(event) {
  if (event.key !== "ArrowLeft" && event.key !== "ArrowRight") return;
  const tabs = [...document.querySelectorAll("#result-tabs [data-tab]")];
  const current = tabs.indexOf(event.target);
  if (current < 0) return;
  event.preventDefault();
  const direction = event.key === "ArrowRight" ? 1 : -1;
  const next = tabs[(current + direction + tabs.length) % tabs.length];
  activateResultTab(next.dataset.tab, true);
}

function pipelineTraceMap(evaluation) {
  return new Map((evaluation?.stepTraces || []).map(trace => [trace.step, trace]));
}

function renderLivePipeline(statusForStep) {
  const steps = PIPELINE_STEPS.map((step, index) => {
    const status = statusForStep(step, index);
    const item = el("li", "live-pipeline__step");
    item.dataset.status = text(status.status, "PENDING");
    item.append(
      el("strong", null, label("steps", step)),
      el("span", null, status.detail || "等待执行")
    );
    return item;
  });
  setChildren(find("#live-pipeline-steps"), steps);
}

function stopPipelineTimer() {
  if (state.pipelineTimer) {
    window.clearInterval(state.pipelineTimer);
    state.pipelineTimer = null;
  }
}

function startLivePipeline() {
  stopPipelineTimer();
  state.evaluationStartedAt = performance.now();
  const panel = find("#live-pipeline");
  panel.hidden = false;

  const update = () => {
    const elapsed = Math.max(0, Math.round(performance.now() - state.evaluationStartedAt));
    const activeIndex = Math.min(PIPELINE_STEPS.length - 1, Math.floor(elapsed / 320));
    find("#pipeline-elapsed").textContent = `${elapsed} ms`;
    find("#pipeline-caption").textContent = `正在执行：${label("steps", PIPELINE_STEPS[activeIndex])}。页面展示等待进度，完成后以服务端真实轨迹覆盖。`;
    renderLivePipeline((step, index) => ({
      status: index < activeIndex ? "SUCCESS" : index === activeIndex ? "ACTIVE" : "PENDING",
      detail: index < activeIndex ? "已提交" : index === activeIndex ? "执行中" : "等待执行"
    }));
  };
  update();
  state.pipelineTimer = window.setInterval(update, 160);
}

function completeLivePipeline(evaluation, durationMs) {
  stopPipelineTimer();
  find("#live-pipeline").hidden = false;
  const traces = pipelineTraceMap(evaluation);
  find("#pipeline-elapsed").textContent = `${durationMs} ms`;
  find("#pipeline-caption").textContent = "服务端真实执行轨迹已返回；每一步的状态与耗时均来自本次评估结果。";
  renderLivePipeline(step => {
    const trace = traces.get(step);
    return {
      status: trace?.status || "SUCCESS",
      detail: trace ? `${label("statuses", trace.status)} · ${text(trace.durationMs, "0")} ms` : "已完成"
    };
  });
}

function failLivePipeline(message) {
  stopPipelineTimer();
  const elapsed = Math.max(0, Math.round(performance.now() - state.evaluationStartedAt));
  find("#pipeline-elapsed").textContent = `${elapsed} ms`;
  find("#pipeline-caption").textContent = `执行中断：${text(message)}`;
  renderLivePipeline((step, index) => ({
    status: index === 0 ? "FAILED" : "PENDING",
    detail: index === 0 ? "请求失败" : "未执行"
  }));
}

function historyFromStorage() {
  const items = storageRead(sessionStorage, HISTORY_KEY, []);
  return Array.isArray(items) ? items.slice(0, MAX_HISTORY_ITEMS) : [];
}

function persistHistory() {
  storageWrite(sessionStorage, HISTORY_KEY, state.history);
}

function renderHistory() {
  const empty = find("#history-empty");
  const clearButton = find("#clear-history");
  empty.hidden = state.history.length > 0;
  clearButton.disabled = state.history.length === 0;
  const buttons = state.history.map(item => {
    const button = el("button", "history-item");
    button.type = "button";
    button.title = `恢复评估 ${text(item.id)}`;
    button.append(
      el("strong", null, `${text(item.policyNo)} · ${text(item.scenarioName)}`),
      el("span", null, `${text(item.displayTime)} · ${text(item.durationMs, "0")} ms`),
      el("span", "history-item__decision", label("decisions", item.evaluation?.decision))
    );
    button.addEventListener("click", () => restoreHistory(item.id));
    return button;
  });
  setChildren(find("#history-list"), buttons);
}

function recordHistory(evaluation, durationMs) {
  const scenario = state.scenarios.find(item => item.policyNo === evaluation.policyNo);
  const now = new Date();
  const entry = {
    id: evaluation.id,
    policyNo: evaluation.policyNo,
    scenarioName: scenario?.name || evaluation.policyNo,
    displayTime: now.toLocaleTimeString("zh-CN", { hour: "2-digit", minute: "2-digit", second: "2-digit" }),
    durationMs,
    evaluation,
    review: null
  };
  state.history = [entry, ...state.history.filter(item => item.id !== evaluation.id)].slice(0, MAX_HISTORY_ITEMS);
  persistHistory();
  renderHistory();
}

function updateHistoryReview(review) {
  const item = state.history.find(entry => entry.id === state.evaluation?.id);
  if (!item) return;
  item.review = review;
  persistHistory();
}

async function restoreHistory(evaluationId) {
  state.savedEvaluationController?.abort();
  state.savedEvaluationController = null;
  const item = state.history.find(entry => entry.id === evaluationId);
  if (!item) return;
  if (state.selectedPolicyNo !== item.policyNo || !state.detail) {
    await selectScenario(item.policyNo);
  }
  if (state.detail?.policyNo !== item.policyNo) return;
  state.evaluation = item.evaluation;
  state.lastDurationMs = item.durationMs;
  renderEvaluation(item.evaluation, state.detail.expectedResult, item.durationMs, {
    record: false,
    review: item.review
  });
  showToast(`已恢复评估 ${item.id}`);
}

function clearHistory() {
  state.history = [];
  persistHistory();
  renderHistory();
  showToast("本次会话记录已清空");
}

function evaluationSummaryText() {
  const evaluation = state.evaluation;
  if (!evaluation) return "";
  return [
    `保单：${evaluation.policyNo}`,
    `结论：${label("decisions", evaluation.decision)}`,
    `风险：${label("risks", evaluation.riskLevel)}（${evaluation.riskScore}/100）`,
    `摘要：${evaluation.summary}`,
    `评估编号：${evaluation.id}`,
    `会话编号：${evaluation.sessionId}`
  ].join("\n");
}

async function copyEvaluationSummary() {
  if (!state.evaluation) return;
  try {
    await navigator.clipboard.writeText(evaluationSummaryText());
    showToast("核保摘要已复制到剪贴板");
  } catch (error) {
    showToast("浏览器未授予剪贴板权限，请使用 JSON 导出");
  }
}

function exportEvaluationJson() {
  if (!state.evaluation) return;
  const payload = JSON.stringify({
    evaluation: state.evaluation,
    humanReview: state.review,
    clientObservedDurationMs: state.lastDurationMs
  }, null, 2);
  const url = URL.createObjectURL(new Blob([payload], { type: "application/json;charset=utf-8" }));
  const link = document.createElement("a");
  link.href = url;
  link.download = `underwriting-evaluation-${state.evaluation.id}.json`;
  link.click();
  window.setTimeout(() => URL.revokeObjectURL(url), 0);
  showToast("核保 JSON 已导出");
}

function configureReportDownload(evaluationId) {
  const action = find("#report-action");
  const link = find("#download-report");
  const encodedId = encodeURIComponent(evaluationId);
  link.href = `${EVALUATION_API}/${encodedId}/report`;
  link.download = `underwriting-report-${text(evaluationId)}.md`;
  action.hidden = false;
}

function defaultReviewOutcome(decision) {
  if (decision === "REJECT") return "REJECTED";
  if (decision === "MANUAL_REVIEW") return "MORE_INFORMATION_REQUIRED";
  return "APPROVED";
}

function renderHumanReviewPanel(evaluation) {
  state.review = null;
  const panel = find("#human-review-panel");
  const form = find("#review-form");
  form.hidden = false;
  form.reset();
  find("#reviewer-id").value = "UW-DEMO-001";
  find("#review-outcome").value = defaultReviewOutcome(evaluation.decision);
  find("#review-guidance").textContent = evaluation.decision === "MANUAL_REVIEW"
    ? "Agent 已要求人工复核。请记录最终处理；该记录创建后不可覆盖。"
    : "可由核保人确认或推翻 Agent 建议；原始建议始终保留。";
  find("#review-status").textContent = "尚未提交人工复核结论。";
  find("#review-result").hidden = true;
  find("#review-result").replaceChildren();
  find("#submit-review").disabled = false;
  panel.hidden = false;
}

function renderHumanReview(review) {
  const result = find("#review-result");
  const conditions = Array.isArray(review.conditions) && review.conditions.length
    ? review.conditions.join("；")
    : "无";
  const card = el("article", "human-review-record");
  card.append(
    el("p", "human-review-record__code", text(review.id)),
    el("h4", null, label("reviewOutcomes", review.outcome)),
    el("p", "human-review-record__relationship", label("reviewRelationships", review.relationship)),
    el("p", null, review.comment),
    el("p", "human-review-record__meta", `复核人员：${text(review.reviewerId)} · 条件/资料：${conditions}`),
    el("p", "human-review-record__meta", `复核时间：${text(review.reviewedAt)}`)
  );
  setChildren(result, [card]);
  result.hidden = false;
  find("#review-form").hidden = true;
  find("#review-status").textContent = "人工复核结论已保存且不可覆盖；重新下载报告即可看到闭环记录。";
  updateHistoryReview(review);
}

function reviewConditions() {
  return find("#review-conditions").value
    .split(/[;；\n]/)
    .map(value => value.trim())
    .filter(Boolean)
    .slice(0, 10);
}

async function submitHumanReview(event) {
  event.preventDefault();
  if (!state.evaluation || state.reviewController) return;

  const evaluationId = state.evaluation.id;
  const controller = new AbortController();
  state.reviewController = controller;
  const button = find("#submit-review");
  button.disabled = true;
  find("#review-form").setAttribute("aria-busy", "true");
  find("#review-status").textContent = "正在保存人工复核结论……";

  try {
    const review = await requestJson(
      `${EVALUATION_API}/${encodeURIComponent(evaluationId)}/review`,
      {
        method: "POST",
        signal: controller.signal,
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          reviewerId: find("#reviewer-id").value.trim(),
          outcome: find("#review-outcome").value,
          comment: find("#review-comment").value.trim(),
          conditions: reviewConditions()
        })
      }
    );
    if (state.reviewController !== controller || state.evaluation?.id !== evaluationId) return;
    state.review = review;
    renderHumanReview(review);
    loadReviewWorklist(state.worklistPage?.page || 0);
  } catch (error) {
    if (error.name !== "AbortError") {
      find("#review-status").textContent = `复核提交失败：${text(error.message)}`;
      button.disabled = false;
    }
  } finally {
    if (state.reviewController === controller) {
      state.reviewController = null;
      find("#review-form").removeAttribute("aria-busy");
    }
  }
}

function renderEvaluation(evaluation, expected, durationMs = 0, options = {}) {
  const { record = true, review = null } = options;
  clearDecisionDrift();
  renderDecisionSummary(evaluation, expected);
  renderDegradations(Array.isArray(evaluation.degradations) ? evaluation.degradations : []);
  renderTextList("reason-list", "核保原因", evaluation.reasons);
  renderTextList("action-list", "建议动作", evaluation.recommendedActions);
  renderRuleHits(Array.isArray(evaluation.ruleHits) ? evaluation.ruleHits : []);
  const evidence = Array.isArray(evaluation.evidence) ? evaluation.evidence : [];
  renderSourceSnapshot(evaluation);
  renderEvidence(evidence);
  renderStepTraces(Array.isArray(evaluation.stepTraces) ? evaluation.stepTraces : []);
  renderToolTraces(Array.isArray(evaluation.toolTraces) ? evaluation.toolTraces : []);
  renderHumanReviewPanel(evaluation);
  if (review) {
    state.review = review;
    renderHumanReview(review);
  }
  renderResultMetrics(evaluation, durationMs);
  completeLivePipeline(evaluation, durationMs);
  configureReportDownload(evaluation.id);
  find("#copy-summary").disabled = false;
  find("#export-json").disabled = false;
  find("#decision-replay-panel").hidden = false;
  find("#run-decision-replay").disabled = false;
  activateResultTab("overview");
  find("#result-content").hidden = false;
  find("#evaluation-status").textContent = `核保完成，评估编号：${text(evaluation.id)}`;
  if (record) {
    recordHistory(evaluation, durationMs);
    loadReviewWorklist(state.worklistPage?.page || 0);
  }
  find("#result-content").scrollIntoView({ behavior: "smooth", block: "start" });
}

async function runDecisionReplay() {
  if (!state.evaluation || state.replayController) return;

  const baseline = state.evaluation;
  const controller = new AbortController();
  state.replayController = controller;
  const button = find("#run-decision-replay");
  button.disabled = true;
  button.textContent = "正在重演……";
  find("#decision-replay-status").textContent =
    "正在用当前业务事实、知识、Prompt、规则集和模型执行新的七步核保。";
  find("#decision-drift-result").hidden = true;
  clearError();

  try {
    const result = await requestJson(
      `${EVALUATION_API}/${encodeURIComponent(baseline.id)}/replays`,
      {
        method: "POST",
        signal: controller.signal,
        headers: { "Idempotency-Key": `replay-${baseline.id}` }
      }
    );
    if (state.replayController !== controller || state.evaluation?.id !== baseline.id) return;
    state.evaluation = result.replay;
    renderEvaluation(result.replay, state.detail?.expectedResult, 0);
    renderDecisionDrift(result.drift);
    find("#decision-drift-result").scrollIntoView({ behavior: "smooth", block: "center" });
  } catch (error) {
    if (error.name !== "AbortError") {
      find("#decision-replay-status").textContent = `决策重演失败：${text(error.message)}`;
      showError(error.message);
    }
  } finally {
    if (state.replayController === controller) {
      state.replayController = null;
      button.disabled = false;
      button.textContent = "再次重演当前决策";
    }
  }
}

function comparisonItem(scenario, evaluation, error = null, durationMs = 0) {
  return { scenario, evaluation, error, durationMs };
}

function successfulComparisonItems(items) {
  return items.filter(item => item.evaluation && !item.error);
}

function comparisonDistribution(items) {
  return items.reduce((counts, item) => {
    const decision = item.evaluation?.decision;
    if (decision) counts[decision] = (counts[decision] || 0) + 1;
    return counts;
  }, {});
}

function renderComparisonSummary(items) {
  const successful = successfulComparisonItems(items);
  const distribution = comparisonDistribution(successful);
  const scores = successful.map(item => clampScore(item.evaluation.riskScore));
  const matches = successful.filter(item =>
    item.evaluation.decision === item.scenario.expectedResult?.decision
  ).length;
  const safetyOverrides = successful.filter(item =>
    item.evaluation.decision !== item.scenario.expectedResult?.decision
      && Array.isArray(item.evaluation.degradations)
      && item.evaluation.degradations.length > 0
  ).length;
  const ruleCount = successful.reduce((total, item) => total + (item.evaluation.ruleHits?.length || 0), 0);
  const evidenceCount = successful.reduce((total, item) => total + (item.evaluation.evidence?.length || 0), 0);
  const scoreRange = scores.length ? `${Math.min(...scores)}–${Math.max(...scores)} 分` : "暂无";
  const values = [
    ["成功场景", `${successful.length}/${items.length}`],
    [
      "结论分布",
      `通过 ${distribution.APPROVE || 0} · 复核 ${distribution.MANUAL_REVIEW || 0} · 拒保 ${distribution.REJECT || 0}`
    ],
    ["符合预期", `${matches}/${successful.length}`],
    ["安全覆盖", `${safetyOverrides} 个`],
    ["风险分范围", scoreRange],
    ["规则命中", `${ruleCount} 条`],
    ["知识证据", `${evidenceCount} 条`]
  ];
  setChildren(find("#comparison-summary"), values.map(([title, value]) => {
    const card = el("article", "comparison-metric");
    card.append(el("span", null, title), el("strong", null, value));
    return card;
  }));
}

function renderComparisonCard(item) {
  const card = el("article", item.error ? "comparison-card comparison-card--failed" : "comparison-card");
  card.append(
    el("p", "result-card__meta", item.scenario.policyNo),
    el("h3", null, item.scenario.name)
  );
  if (item.error) {
    card.append(
      el("p", "comparison-card__error", `运行失败：${item.error}`),
      el("p", "result-card__meta", "部分场景运行失败，不影响其他场景结果。")
    );
    return card;
  }

  const evaluation = item.evaluation;
  const score = clampScore(evaluation.riskScore);
  const badge = el("p", "decision-badge", label("decisions", evaluation.decision));
  badge.dataset.decision = text(evaluation.decision, "UNKNOWN");
  const matches = evaluation.decision === item.scenario.expectedResult?.decision;
  const safetyOverride = !matches
    && Array.isArray(evaluation.degradations)
    && evaluation.degradations.length > 0;
  const progress = el("div", "comparison-risk");
  progress.setAttribute("role", "progressbar");
  progress.setAttribute("aria-label", `${item.scenario.name}风险分`);
  progress.setAttribute("aria-valuemin", "0");
  progress.setAttribute("aria-valuemax", "100");
  progress.setAttribute("aria-valuenow", String(score));
  const fill = el("span", "comparison-risk__fill");
  fill.style.width = `${score}%`;
  progress.append(fill);

  const facts = el("dl", "comparison-facts");
  [
    ["风险等级", label("risks", evaluation.riskLevel)],
    ["风险分", `${score} 分`],
    ["规则命中", `${evaluation.ruleHits?.length || 0} 条`],
    ["知识证据", `${evaluation.evidence?.length || 0} 条`],
    ["评估耗时", `${item.durationMs} ms`]
  ].forEach(([term, value]) => facts.append(el("dt", null, term), el("dd", null, value)));

  const detailButton = el("button", "comparison-card__detail", `查看单场景详情：${item.scenario.name}`);
  detailButton.type = "button";
  detailButton.addEventListener("click", async () => {
    await selectScenario(item.scenario.policyNo);
    find(".detail-panel").scrollIntoView({ behavior: "smooth", block: "start" });
  });
  card.append(
    badge,
    el(
      "p",
      matches
        ? "comparison comparison--match"
        : safetyOverride
          ? "comparison comparison--safety"
          : "comparison comparison--different",
      matches
        ? "实际结论与预期一致"
        : safetyOverride
          ? `安全降级已覆盖常规预期：${label("decisions", item.scenario.expectedResult?.decision)}`
        : `实际结论与预期不同：${label("decisions", item.scenario.expectedResult?.decision)}`
    ),
    progress,
    facts,
    detailButton
  );
  return card;
}

function setDetailLoading(loading) {
  const status = find("#detail-status");
  status.hidden = !loading && Boolean(state.detail);
  status.textContent = loading ? "正在读取场景事实……" : state.detail ? "场景事实加载完成。" : "场景加载失败。";
  find("#run-evaluation").disabled = loading || !state.detail;
}

function setEvaluationLoading(loading) {
  const button = find("#run-evaluation");
  button.disabled = loading || !state.detail || !find("#question-input").value.trim();
  button.textContent = loading ? "核保 Agent 运行中……" : "▶ 运行智能核保";
  if (loading) {
    find("#evaluation-status").textContent = "正在执行七步核保流程，请稍候……";
  }
}

function setComparisonLoading(loading, completed = 0, total = state.scenarios.length) {
  const button = find("#run-comparison");
  button.disabled = loading || state.scenarios.length === 0;
  button.textContent = loading
    ? `正在对比 ${completed}/${total}……`
    : state.comparisonResults.length
      ? "重新对比全部场景"
      : "对比全部场景";
  if (loading) {
    find("#comparison-status").textContent =
      `正在顺序运行第 ${Math.min(completed + 1, total)} 组，共 ${total} 组。`;
  }
}

function clearError() {
  const panel = find("#error-panel");
  panel.hidden = true;
  panel.replaceChildren();
}

function showError(message) {
  const panel = find("#error-panel");
  panel.textContent = `操作未完成：${text(message, "未知错误")} 请检查服务状态后重试。`;
  panel.hidden = false;
}

function resetEvaluation() {
  stopPipelineTimer();
  state.reviewController?.abort();
  state.reviewController = null;
  state.replayController?.abort();
  state.replayController = null;
  state.review = null;
  state.evaluation = null;
  state.lastDurationMs = 0;
  find("#result-content").hidden = true;
  find("#live-pipeline").hidden = true;
  find("#live-pipeline-steps").replaceChildren();
  find("#result-metrics").replaceChildren();
  find("#copy-summary").disabled = true;
  find("#export-json").disabled = true;
  find("#decision-replay-panel").hidden = true;
  find("#run-decision-replay").disabled = true;
  find("#run-decision-replay").textContent = "重演当前决策";
  clearDecisionDrift();
  find("#report-action").hidden = true;
  const reportLink = find("#download-report");
  reportLink.removeAttribute("href");
  reportLink.removeAttribute("download");
  find("#human-review-panel").hidden = true;
  find("#review-form").reset();
  find("#review-form").removeAttribute("aria-busy");
  find("#review-form").hidden = false;
  find("#review-result").hidden = true;
  find("#review-result").replaceChildren();
  find("#submit-review").disabled = false;
  [
    "#decision-summary",
    "#degradation-list",
    "#reason-list",
    "#action-list",
    "#rule-hit-list",
    "#source-snapshot",
    "#evidence-list",
    "#step-timeline",
    "#tool-trace-list"
  ].forEach(selector => find(selector).replaceChildren());
  activateResultTab("overview");
  clearError();
  find("#evaluation-status").textContent = "尚未运行核保。";
}

async function selectScenario(policyNo) {
  if (!policyNo) return;
  state.savedEvaluationController?.abort();
  state.savedEvaluationController = null;
  find("#worklist-open-status").textContent = "";

  state.detailController?.abort();
  state.evaluationController?.abort();
  state.evaluationController = null;
  const controller = new AbortController();
  state.detailController = controller;
  state.selectedPolicyNo = policyNo;
  state.detail = null;
  find("#scenario-detail").hidden = true;
  renderScenarioButtons();
  resetEvaluation();
  setDetailLoading(true);

  try {
    const detail = await requestJson(`${SCENARIO_API}/${encodeURIComponent(policyNo)}`, {
      signal: controller.signal
    });
    if (state.detailController !== controller || state.selectedPolicyNo !== policyNo) return;
    state.detail = detail;
    renderScenarioDetail(detail);
  } catch (error) {
    if (error.name !== "AbortError") {
      find("#detail-status").textContent = `场景加载失败：${text(error.message)}`;
      showError(error.message);
    }
  } finally {
    if (state.detailController === controller) {
      state.detailController = null;
      setDetailLoading(false);
    }
  }
}

async function runEvaluation() {
  if (!state.detail || state.evaluationController) return;
  state.savedEvaluationController?.abort();
  state.savedEvaluationController = null;

  const detail = state.detail;
  const question = find("#question-input").value.trim();
  if (!question) {
    showToast("请先填写核保任务");
    find("#question-input").focus();
    return;
  }
  const controller = new AbortController();
  state.evaluationController = controller;
  clearError();
  setEvaluationLoading(true);
  startLivePipeline();

  try {
    const evaluation = await requestJson(EVALUATION_API, {
      method: "POST",
      signal: controller.signal,
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        policyNo: detail.policyNo,
        question
      })
    });
    if (state.evaluationController !== controller || state.detail?.policyNo !== detail.policyNo) return;
    state.evaluation = evaluation;
    state.lastDurationMs = Math.max(0, Math.round(performance.now() - state.evaluationStartedAt));
    renderEvaluation(evaluation, detail.expectedResult, state.lastDurationMs);
  } catch (error) {
    if (error.name !== "AbortError") {
      find("#evaluation-status").textContent = "核保运行失败，可以直接重试。";
      failLivePipeline(error.message);
      showError(error.message);
    }
  } finally {
    if (state.evaluationController === controller) {
      state.evaluationController = null;
      setEvaluationLoading(false);
    }
  }
}

async function runComparison() {
  if (!state.scenarios.length || state.comparisonController) return;

  const controller = new AbortController();
  state.comparisonController = controller;
  state.comparisonResults = [];
  find("#comparison-panel").hidden = false;
  find("#comparison-summary").replaceChildren();
  find("#comparison-grid").replaceChildren();
  setComparisonLoading(true, 0);

  try {
    for (const scenario of state.scenarios) {
      const startedAt = performance.now();
      try {
        const evaluation = await requestJson(EVALUATION_API, {
          method: "POST",
          signal: controller.signal,
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ policyNo: scenario.policyNo, question: scenario.question })
        });
        state.comparisonResults.push(comparisonItem(
          scenario,
          evaluation,
          null,
          Math.max(0, Math.round(performance.now() - startedAt))
        ));
      } catch (error) {
        if (error.name === "AbortError") throw error;
        state.comparisonResults.push(comparisonItem(scenario, null, text(error.message, "未知错误")));
      }
      setComparisonLoading(true, state.comparisonResults.length);
    }

    renderComparisonSummary(state.comparisonResults);
    loadReviewWorklist(state.worklistPage?.page || 0);
    setChildren(find("#comparison-grid"), state.comparisonResults.map(renderComparisonCard));
    const failed = state.comparisonResults.filter(item => item.error).length;
    find("#comparison-status").textContent = failed
      ? `对比完成：成功 ${state.comparisonResults.length - failed} 组，部分场景运行失败 ${failed} 组。`
      : `对比完成：${state.comparisonResults.length} 组场景均已运行。`;
    find("#comparison-panel").scrollIntoView({ behavior: "smooth", block: "start" });
  } catch (error) {
    if (error.name !== "AbortError") {
      find("#comparison-status").textContent = `对比中断：${text(error.message)}`;
    }
  } finally {
    if (state.comparisonController === controller) {
      state.comparisonController = null;
      setComparisonLoading(false);
    }
  }
}

async function initialize() {
  state.history = historyFromStorage();
  renderHistory();
  checkServiceHealth();
  loadReviewWorklist();
  try {
    const scenarios = await requestJson(SCENARIO_API);
    if (!Array.isArray(scenarios) || scenarios.length === 0) {
      throw new Error("当前没有可用的演示场景。");
    }
    state.scenarios = scenarios;
    renderScenarioButtons();
    if (!state.savedEvaluationController && !state.evaluation && !state.selectedPolicyNo) {
      await selectScenario(state.scenarios[0].policyNo);
    }
    find("#run-comparison").disabled = false;
    find("#comparison-status").textContent = `可对比 ${state.scenarios.length} 组虚构场景。`;
  } catch (error) {
    showError(error.message);
    find("#scenario-status").textContent = "场景目录加载失败，请刷新页面重试。";
    find("#detail-status").textContent = "服务暂时不可用。";
  }
}

function handleGlobalShortcut(event) {
  if ((event.metaKey || event.ctrlKey) && event.key === "Enter") {
    event.preventDefault();
    runEvaluation();
    return;
  }
  if (event.metaKey || event.ctrlKey || event.altKey || event.target.matches("input, textarea, select")) return;
  const scenarioIndex = Number(event.key) - 1;
  if (scenarioIndex >= 0 && scenarioIndex < state.scenarios.length) {
    event.preventDefault();
    selectScenario(state.scenarios[scenarioIndex].policyNo);
  }
}

applyTheme(preferredTheme());
find("#worklist-filters").addEventListener("submit", event => {
  event.preventDefault();
  loadReviewWorklist();
});
for (const selector of ["#worklist-status-filter", "#worklist-decision-filter"]) {
  find(selector).addEventListener("change", () => loadReviewWorklist());
}
find("#worklist-previous").addEventListener("click", () => loadReviewWorklist((state.worklistPage?.page || 0) - 1));
find("#worklist-next").addEventListener("click", () => loadReviewWorklist((state.worklistPage?.page || 0) + 1));
find("#run-evaluation").addEventListener("click", runEvaluation);
find("#run-comparison").addEventListener("click", runComparison);
find("#run-decision-replay").addEventListener("click", runDecisionReplay);
find("#review-form").addEventListener("submit", submitHumanReview);
find("#question-input").addEventListener("input", updateQuestionCount);
find("#reset-question").addEventListener("click", resetQuestion);
find("#theme-toggle").addEventListener("click", toggleTheme);
find("#clear-history").addEventListener("click", clearHistory);
find("#copy-summary").addEventListener("click", copyEvaluationSummary);
find("#export-json").addEventListener("click", exportEvaluationJson);
find("#scenario-filters").addEventListener("click", event => {
  const button = event.target.closest("[data-filter]");
  if (button) setScenarioFilter(button.dataset.filter);
});
find("#result-tabs").addEventListener("click", event => {
  const button = event.target.closest("[data-tab]");
  if (button) activateResultTab(button.dataset.tab);
});
find("#result-tabs").addEventListener("keydown", handleTabKeydown);
document.addEventListener("keydown", handleGlobalShortcut);
initialize();
