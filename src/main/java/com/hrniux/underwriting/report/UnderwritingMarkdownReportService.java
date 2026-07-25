package com.hrniux.underwriting.report;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.hrniux.underwriting.agent.AgentStep;
import com.hrniux.underwriting.agent.DegradationNotice;
import com.hrniux.underwriting.agent.Evidence;
import com.hrniux.underwriting.agent.StepStatus;
import com.hrniux.underwriting.agent.StepTrace;
import com.hrniux.underwriting.agent.UnderwritingEvaluation;
import com.hrniux.underwriting.model.ModelResponse;
import com.hrniux.underwriting.rag.DocumentType;
import com.hrniux.underwriting.rag.RetrievalMode;
import com.hrniux.underwriting.rule.Decision;
import com.hrniux.underwriting.rule.RiskLevel;
import com.hrniux.underwriting.rule.RuleResult;
import com.hrniux.underwriting.rule.RuleSeverity;
import com.hrniux.underwriting.review.AgentReviewRelationship;
import com.hrniux.underwriting.review.HumanReview;
import com.hrniux.underwriting.review.HumanReviewOutcome;
import com.hrniux.underwriting.tool.ToolCallStatus;
import com.hrniux.underwriting.tool.ToolCallTrace;
import com.hrniux.underwriting.tool.ToolName;

@Service
public class UnderwritingMarkdownReportService {

    public String render(UnderwritingEvaluation evaluation) {
        return render(evaluation, Optional.empty());
    }

    public String render(UnderwritingEvaluation evaluation, Optional<HumanReview> humanReview) {
        Objects.requireNonNull(evaluation, "evaluation must not be null");
        Objects.requireNonNull(humanReview, "humanReview must not be null");

        StringBuilder report = new StringBuilder();
        appendHeader(report, evaluation);
        appendDecision(report, evaluation);
        appendDegradations(report, evaluation.degradations());
        appendHumanReview(report, humanReview);
        appendNarrative(report, "模型摘要", evaluation.summary());
        appendList(report, "核保原因", evaluation.reasons());
        appendList(report, "建议动作", evaluation.recommendedActions());
        appendRules(report, evaluation.ruleHits());
        appendEvidence(report, evaluation.evidence());
        appendSteps(report, evaluation.stepTraces());
        appendTools(report, evaluation.toolTraces());
        appendModel(report, evaluation);
        return report.append("## 免责声明\n\n")
                .append("本报告仅用于技术学习和面试演示，不构成真实保险核保意见。")
                .append("报告中的业务数据、规则阈值和条款均为虚构内容。\n")
                .toString();
    }

    private void appendHeader(StringBuilder report, UnderwritingEvaluation evaluation) {
        report.append("# 财险智能核保评估报告\n\n")
                .append("> 本报告根据已保存的核保评估和可选人工复核记录确定性生成；下载不会重新执行模型或规则。\n\n")
                .append("## 基本信息\n\n")
                .append("| 字段 | 内容 |\n")
                .append("|---|---|\n");
        appendRow(report, "评估编号", evaluation.id());
        appendRow(report, "会话编号", evaluation.sessionId());
        appendRow(report, "保单号", evaluation.policyNo());
        appendRow(report, "核保问题", evaluation.question());
        appendRow(report, "评估时间（UTC）", evaluation.createdAt());
        appendRow(report, "决策重演基线",
                evaluation.replay() ? code(evaluation.replayedFromEvaluationId()) : "非重演评估");
        report.append('\n');
    }

    private void appendDecision(StringBuilder report, UnderwritingEvaluation evaluation) {
        report.append("## 核保结论\n\n")
                .append("| 项目 | 结果 |\n")
                .append("|---|---|\n");
        appendRow(report, "Agent 辅助建议", decisionLabel(evaluation.decision()));
        appendRow(report, "风险等级", riskLabel(evaluation.riskLevel()));
        appendRow(report, "风险分", evaluation.riskScore() + " / 100");
        report.append('\n');
    }

    private void appendHumanReview(StringBuilder report, Optional<HumanReview> humanReview) {
        report.append("## 人工复核闭环\n\n");
        if (humanReview.isEmpty()) {
            report.append("- 尚未提交人工复核结论；Agent 输出仍是辅助建议。\n\n");
            return;
        }

        HumanReview review = humanReview.orElseThrow();
        report.append("| 字段 | 内容 |\n")
                .append("|---|---|\n");
        appendRow(report, "复核编号", code(review.id()));
        appendRow(report, "复核人员编号", code(review.reviewerId()));
        appendRow(report, "人工处理结论", reviewOutcomeLabel(review.outcome()));
        appendRow(report, "与 Agent 建议关系", relationshipLabel(review.relationship()));
        appendRow(report, "复核时间（UTC）", review.reviewedAt());
        appendRow(report, "复核说明", review.comment());
        report.append('\n')
                .append("### 承保条件或补充资料\n\n");
        if (review.conditions().isEmpty()) {
            report.append("- 无\n\n");
        }
        else {
            review.conditions().forEach(condition -> report.append("- ").append(safeText(condition)).append('\n'));
            report.append('\n');
        }
    }

    private void appendNarrative(StringBuilder report, String title, String value) {
        report.append("## ").append(title).append("\n\n")
                .append(safeText(value)).append("\n\n");
    }

    private void appendDegradations(StringBuilder report, List<DegradationNotice> degradations) {
        report.append("## 数据质量与安全降级\n\n");
        if (degradations.isEmpty()) {
            report.append("- 本次评估未发生数据源降级。\n\n");
            return;
        }
        report.append("| 告警编码 | 工具 | 工具错误码 | 决策下限 | 处置说明 |\n")
                .append("|---|---|---|---|---|\n");
        for (DegradationNotice degradation : degradations) {
            appendRow(report,
                    code(degradation.code()),
                    toolLabel(degradation.toolName()),
                    code(degradation.errorCode()),
                    decisionLabel(degradation.decisionFloor()),
                    degradation.message());
        }
        report.append('\n');
    }

    private void appendList(StringBuilder report, String title, List<String> values) {
        report.append("## ").append(title).append("\n\n");
        if (values.isEmpty()) {
            report.append("- 无\n\n");
            return;
        }
        values.forEach(value -> report.append("- ").append(safeText(value)).append('\n'));
        report.append('\n');
    }

    private void appendRules(StringBuilder report, List<RuleResult> rules) {
        report.append("## 规则命中\n\n")
                .append("| 规则编码 | 严重程度 | 规则决策 | 风险分影响 | 原因 |\n")
                .append("|---|---|---|---:|---|\n");
        if (rules.isEmpty()) {
            report.append("| 无 | 无 | 无 | 无 | 无 |\n\n");
            return;
        }
        for (RuleResult rule : rules) {
            appendRow(report,
                    code(rule.code()),
                    severityLabel(rule.severity()),
                    decisionLabel(rule.decision()),
                    signed(rule.scoreImpact()),
                    rule.reason());
        }
        report.append('\n');
    }

    private void appendEvidence(StringBuilder report, List<Evidence> evidenceItems) {
        report.append("## 知识证据\n\n")
                .append("| 文档类型 | 标题 | 知识版本 | 综合分 | 向量分 | 词法分 | 检索解释 | 摘录 | 文档 / 分块 |\n")
                .append("|---|---|---:|---:|---:|---:|---|---|---|\n");
        if (evidenceItems.isEmpty()) {
            report.append("| 无 | 无 | 无 | 无 | 无 | 无 | 无 | 无 | 无 |\n\n");
            return;
        }
        for (Evidence evidence : evidenceItems) {
            appendRow(report,
                    documentLabel(evidence.type()),
                    evidence.title(),
                    knowledgeVersion(evidence.knowledgeVersion()),
                    percentage(evidence.score()),
                    percentage(evidence.vectorScore()),
                    percentage(evidence.lexicalScore()),
                    retrievalExplanation(evidence),
                    evidence.excerpt(),
                    code(evidence.documentId()) + " / " + code(evidence.chunkId()));
        }
        report.append('\n');
    }

    private void appendSteps(StringBuilder report, List<StepTrace> traces) {
        report.append("## 七步 Agent 执行轨迹\n\n")
                .append("| 序号 | 步骤 | 状态 | 耗时 | 开始时间（UTC） | 错误码 |\n")
                .append("|---:|---|---|---:|---|---|\n");
        if (traces.isEmpty()) {
            report.append("| 无 | 无 | 无 | 无 | 无 | 无 |\n\n");
            return;
        }
        for (int index = 0; index < traces.size(); index++) {
            StepTrace trace = traces.get(index);
            appendRow(report,
                    index + 1,
                    stepLabel(trace.step()),
                    stepStatusLabel(trace.status()),
                    trace.durationMs() + " ms",
                    trace.startedAt(),
                    trace.errorCode());
        }
        report.append('\n');
    }

    private void appendTools(StringBuilder report, List<ToolCallTrace> traces) {
        report.append("## 工具调用记录\n\n")
                .append("| 工具 | 状态 | 耗时 | 输入摘要 | 输出摘要 | 开始时间（UTC） | 错误码 |\n")
                .append("|---|---|---:|---|---|---|---|\n");
        if (traces.isEmpty()) {
            report.append("| 无 | 无 | 无 | 无 | 无 | 无 | 无 |\n\n");
            return;
        }
        for (ToolCallTrace trace : traces) {
            appendRow(report,
                    toolLabel(trace.toolName()),
                    toolStatusLabel(trace.status()),
                    trace.durationMs() + " ms",
                    trace.inputSummary(),
                    trace.outputSummary(),
                    trace.startedAt(),
                    trace.errorCode());
        }
        report.append('\n');
    }

    private void appendModel(StringBuilder report, UnderwritingEvaluation evaluation) {
        ModelResponse response = evaluation.modelResponse();
        report.append("## 模型执行信息\n\n")
                .append("| 字段 | 内容 |\n")
                .append("|---|---|\n");
        appendRow(report, "提供方", response.provider());
        appendRow(report, "模型", response.model());
        appendRow(report, "尝试次数", response.attempts());
        appendRow(report, "是否使用降级", response.fallbackUsed() ? "是" : "否");
        appendRow(report, "提示词代码", code(response.prompt().code()));
        appendRow(report, "提示词版本",
                response.prompt().available() ? "v" + response.prompt().version() : "历史记录不可用");
        appendRow(report, "模板 SHA-256",
                response.prompt().available() ? code(response.prompt().templateSha256()) : "历史记录不可用");
        appendRow(report, "规则集版本",
                evaluation.ruleSet().available() ? code(evaluation.ruleSet().version()) : "历史记录不可用");
        appendRow(report, "规则集 SHA-256",
                evaluation.ruleSet().available() ? code(evaluation.ruleSet().sha256()) : "历史记录不可用");
        appendRow(report, "规则数量",
                evaluation.ruleSet().available() ? evaluation.ruleSet().ruleCount() : "历史记录不可用");
        report.append('\n');
    }

    private String knowledgeVersion(int version) {
        return version > 0 ? "v" + version : "历史记录不可用";
    }

    private String retrievalExplanation(Evidence evidence) {
        String mode = retrievalModeLabel(evidence.retrievalMode());
        if (evidence.matchedTerms().isEmpty()) {
            return mode + "；无词法命中词";
        }
        return mode + "；命中：" + String.join("、", evidence.matchedTerms());
    }

    private String retrievalModeLabel(RetrievalMode mode) {
        String chinese = switch (mode) {
            case HYBRID -> "混合检索";
            case VECTOR_ONLY -> "仅向量";
            case LEXICAL_ONLY -> "仅词法";
            case UNKNOWN -> "历史记录不可用";
        };
        return label(chinese, mode);
    }

    private void appendRow(StringBuilder report, Object... cells) {
        report.append('|');
        for (Object cell : cells) {
            report.append(' ').append(tableCell(cell)).append(" |");
        }
        report.append('\n');
    }

    private String tableCell(Object value) {
        return safeText(value).replace("|", "\\|");
    }

    private String safeText(Object value) {
        if (value == null || value.toString().isBlank()) {
            return "无";
        }
        return value.toString()
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\\", "\\\\")
                .replace("\n", "<br>");
    }

    private String decisionLabel(Decision decision) {
        String chinese = switch (decision) {
            case APPROVE -> "自动通过";
            case MANUAL_REVIEW -> "人工复核";
            case REJECT -> "拒保";
        };
        return label(chinese, decision);
    }

    private String riskLabel(RiskLevel riskLevel) {
        String chinese = switch (riskLevel) {
            case LOW -> "低风险";
            case MEDIUM -> "中风险";
            case HIGH -> "高风险";
            case CRITICAL -> "极高风险";
        };
        return label(chinese, riskLevel);
    }

    private String severityLabel(RuleSeverity severity) {
        String chinese = switch (severity) {
            case INFO -> "提示";
            case WARNING -> "警告";
            case HIGH -> "高风险";
            case CRITICAL -> "严重";
        };
        return label(chinese, severity);
    }

    private String documentLabel(DocumentType type) {
        String chinese = switch (type) {
            case PRODUCT_CLAUSE -> "保险条款";
            case UNDERWRITING_RULE -> "核保规则";
            case RISK_GUIDE -> "风险指引";
            case HISTORICAL_CASE -> "历史案例";
        };
        return label(chinese, type);
    }

    private String stepLabel(AgentStep step) {
        String chinese = switch (step) {
            case QUESTION_UNDERSTANDING -> "理解核保问题";
            case BUSINESS_DATA_COLLECTION -> "采集五类业务事实";
            case KNOWLEDGE_RETRIEVAL -> "检索核保知识";
            case RISK_ANALYSIS -> "分析综合风险";
            case RULE_VALIDATION -> "执行确定性规则";
            case RECOMMENDATION_GENERATION -> "生成核保建议";
            case RESULT_PERSISTENCE -> "保存可审计结果";
        };
        return label(chinese, step);
    }

    private String stepStatusLabel(StepStatus status) {
        String chinese = switch (status) {
            case SUCCESS -> "成功";
            case DEGRADED -> "降级完成";
            case FAILED -> "失败";
        };
        return label(chinese, status);
    }

    private String toolLabel(ToolName tool) {
        String chinese = switch (tool) {
            case GET_POLICY -> "保单信息工具";
            case GET_QUOTATION -> "报价信息工具";
            case GET_UNDERWRITING_HISTORY -> "历史核保工具";
            case GET_SURVEY_REPORT -> "风险查勘工具";
            case GET_DISASTER_RISK -> "灾害风险工具";
            case VALIDATE_RULES -> "规则校验工具";
        };
        return label(chinese, tool);
    }

    private String toolStatusLabel(ToolCallStatus status) {
        return label(status == ToolCallStatus.SUCCESS ? "成功" : "失败", status);
    }

    private String reviewOutcomeLabel(HumanReviewOutcome outcome) {
        String chinese = switch (outcome) {
            case APPROVED -> "同意承保";
            case REJECTED -> "拒绝承保";
            case MORE_INFORMATION_REQUIRED -> "要求补充资料";
        };
        return label(chinese, outcome);
    }

    private String relationshipLabel(AgentReviewRelationship relationship) {
        String chinese = switch (relationship) {
            case CONFIRMED -> "确认 Agent 建议";
            case OVERRIDDEN -> "推翻 Agent 建议";
            case RESOLVED_MANUAL_REVIEW -> "完成人工复核";
            case CONTINUED_MANUAL_REVIEW -> "继续补充资料";
        };
        return label(chinese, relationship);
    }

    private String label(String chinese, Enum<?> value) {
        return chinese + "（`" + value.name() + "`）";
    }

    private String code(String value) {
        return "`" + value + "`";
    }

    private String signed(int value) {
        return value >= 0 ? "+" + value : Integer.toString(value);
    }

    private String percentage(double score) {
        double bounded = Math.max(0, Math.min(1, score));
        return String.format(Locale.ROOT, "%.0f%%", bounded * 100);
    }
}
