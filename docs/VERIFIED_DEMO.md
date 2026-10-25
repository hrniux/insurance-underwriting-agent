# 一键可验证的离线核保 Demo

本演示使用现有 Java 服务、虚构保单、Hash Embedding、内存知识库和确定性 Mock 模型。无需模型 Key；验证的是实际 HTTP 链路、规则、检索与审计契约。它没有测试真实 LLM 输出质量，也不能用于真实保险决策。

## 一条命令

前提：Mac/Linux、Bash、Python 3、JDK 21+。Maven Wrapper 会下载 Maven 和项目依赖，首次构建需要网络；“离线”指应用运行不需要外部模型或业务系统。进入仓库根目录执行：

```bash
bash scripts/run-verified-demo.sh
```

脚本使用 `./mvnw -B verify` 构建并运行现有 Java 测试，在 `127.0.0.1:18080` 启动自己的 Java 进程，强制默认 Profile、Mock 模型和禁止模型回退，等待应用启动与健康，再执行 Python 验收。成功、失败或中断时均清理自己启动的 Java 进程。端口被占用时直接失败，不复用已有服务。构建上限 600 秒，启动最多 60 次有界探测，单次 API 请求超时 15 秒。

```bash
DEMO_PORT=18081 bash scripts/run-verified-demo.sh
bash scripts/run-verified-demo.sh --output-dir target/demo-evidence/my-new-run
```

每次自动生成新的证据目录及幂等键。指定目录必须为空，历史证据不会被覆盖。构建使用 Maven 自己支持的 `MAVEN_ARGS` / `MAVEN_OPTS` 配置入口，例如私有镜像可用 `MAVEN_ARGS='-s /path/to/settings.xml'`；不要把凭据放进命令或报告。

## 验收已有服务

```bash
python3 scripts/verify-demo.py --base-url http://127.0.0.1:8080
python3 scripts/verify-demo.py --base-url http://127.0.0.1:8080 \
  --output-dir target/demo-evidence/manual-new-run
```

独立验收命令不会启动或停止服务。服务须使用完整的默认虚构数据与 Mock 模型；真实模型、回退模型或降级场景不能通过本验收。验收器直接连接指定地址，不继承 HTTP 代理。它无法独立证明外部服务的代码版本；`gitCommit` 记录的是运行脚本所在工作树的 HEAD，未提交改动也不会包含在该提交号中。一键启动路径会从当前工作树重新构建。

## 通过条件

| 保单 | 决策 | 风险 | 分数 |
|---|---|---|---|
| P-1001 | MANUAL_REVIEW | HIGH | 70 |
| P-2001 | APPROVE | LOW | 10 |
| P-3001 | MANUAL_REVIEW | MEDIUM | 40 |
| P-4001 | REJECT | HIGH | 60 |

固定结果来自验收器中的独立预期，不采信场景目录给出的预期作为判定依据。每个场景还检查七步成功轨迹、六个不同的成功工具轨迹、已发布知识版本、混合检索解释、Prompt/规则集版本与 SHA-256、实际模型响应中的 `provider=mock` 和 `fallbackUsed=false`，并拒绝降级结果。

每张保单均检查首次提交 201、同键同参返回同一保存结果且 200、同键异参 409 与冲突码；重新 GET 的结果必须与首次响应相同，下载的 Markdown 报告必须包含该结果编号、保单、摘要、风险分与来源指纹。另对 P-1001 执行关联基线的决策重演，要求 `STABLE`；执行两条黄金问题，要求 `Recall@4=1`、`MRR=1` 与质量门禁通过。小型黄金集只能说明这两条教学问题的确定性表现。

## 查看证据与失败

默认目录为 `target/demo-evidence/<UTC时间-随机标识>/`：

- `summary.json`：总通过状态、逐项检查、UTC 时间、工作树 HEAD、实际 API 返回的模型来源及评估编号。
- `*.body`：原始 HTTP 响应体，包含保存结果、幂等重放、冲突、报告、重演与 RAG 结果。
- `*.http.json`：HTTP 状态及用于验收的响应头白名单；不保存认证头。
- `build.log` / `application.log`：仅一键启动时产生的构建与应用日志。

全部断言成功才会输出 `PASS` 和退出码 0。HTTP 错误、非 JSON、缺失来源、错误结论或启动失败都会非零退出，并保存 `passed:false` 的摘要；已经收到的原始响应会保留。无响应的连接错误无法产生响应体。参数错误或复用非空证据目录会直接失败，不覆盖原文件。

`target/` 已被 Git 忽略。证据使用虚构数据，仍应在分享前检查本机日志；脚本不把环境变量或模型 Key 写入摘要。

## 回归检查

```bash
python3 -m unittest discover -s scripts/tests -v
bash -n scripts/run-verified-demo.sh
```

Python 测试覆盖独立的错误结论、缺失或损坏的来源、真实模型/模型回退、降级轨迹、重复工具/步骤，以及 HTTP 失败和非 JSON 的证据保存。Mac/Linux 是脚本的目标平台；已执行的具体环境与结果应以交付报告为准，不能把 Linux 验证说成 Mac 本机实测。
