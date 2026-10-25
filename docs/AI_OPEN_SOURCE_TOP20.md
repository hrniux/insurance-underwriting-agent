# 值得关注的20个AI开源仓库

核查日期：2026-10-25。根据你的[个人介绍](https://github.com/hrniux/hrniux)和[核保Agent](https://github.com/hrniux/insurance-underwriting-agent)，排序侧重Java/Spring、RAG、可解释Agent及Mac本地开发。这是有依据的精选清单，未逐一浏览GitHub全部仓库，也不声称存在客观唯一的前20名。

## 筛选方法

综合比较实际落地价值、与现有项目的关联、复现路径、维护/发布、许可证和路线互补性。先用GitHub的LLM/RAG主题搜索发现候选，再并行比较基础推理、Agent/RAG/Java、文档解析与评测三组项目。对最终20项读取官方README、许可证及元数据/发布情况。Star数仅在JSON中作日期快照，没有按Star数排序。没有逐一安装运行20个项目。

## 推荐顺序

| 顺序 | 项目 | 主体代码许可 | 主要价值 | 最近推送 |
|---|---|---|---|---|
| 1 | [spring-projects/spring-ai](https://github.com/spring-projects/spring-ai) | Apache-2.0 | Spring 业务应用的模型、RAG、MCP 集成；与你的核保项目直接相关。 | 2026-10-23 |
| 2 | [ollama/ollama](https://github.com/ollama/ollama) | MIT | 在 Mac 快速启动本地模型 HTTP API。 | 2026-10-24 |
| 3 | [promptfoo/promptfoo](https://github.com/promptfoo/promptfoo) | MIT | 为提示词、模型和 Agent 建立可自动判定的回归评测。 | 2026-10-25 |
| 4 | [langchain-ai/langgraph](https://github.com/langchain-ai/langgraph) | MIT | 实现可持久化、可恢复、支持人工介入的 Agent 流程。 | 2026-10-25 |
| 5 | [docling-project/docling](https://github.com/docling-project/docling) | MIT | 将 PDF 版面、表格、OCR 内容转为 RAG 可用资料。 | 2026-10-23 |
| 6 | [qdrant/qdrant](https://github.com/qdrant/qdrant) | Apache-2.0 | 语义检索、条件过滤与混合搜索的服务化向量数据库。 | 2026-10-23 |
| 7 | [modelcontextprotocol/java-sdk](https://github.com/modelcontextprotocol/java-sdk) | MIT | 用标准协议把 Java 业务能力暴露给多个 AI 客户端。 | 2026-10-23 |
| 8 | [ggml-org/llama.cpp](https://github.com/ggml-org/llama.cpp) | MIT | GGUF、量化和 Apple Silicon 本地推理的基础组件。 | 2026-10-25 |
| 9 | [huggingface/transformers](https://github.com/huggingface/transformers) | Apache-2.0 | 文本、图像、音频等模型应用与实验的核心生态。 | 2026-10-25 |
| 10 | [langchain4j/langchain4j](https://github.com/langchain4j/langchain4j) | Apache-2.0 | 以 Java 接口、POJO 和注解实现模型调用与工具/RAG集成。 | 2026-10-23 |
| 11 | [ml-explore/mlx-lm](https://github.com/ml-explore/mlx-lm) | MIT | Apple Silicon 上的 Python LLM 推理、量化与 LoRA 微调。 | 2026-10-24 |
| 12 | [deepset-ai/haystack](https://github.com/deepset-ai/haystack) | Apache-2.0 | 可组合、可评估的检索、重排和 RAG 流水线。 | 2026-10-24 |
| 13 | [huggingface/text-embeddings-inference](https://github.com/huggingface/text-embeddings-inference) | Apache-2.0 | 独立部署 embedding、rerank 服务，为真实 RAG 替换 Hash Embedding。 | 2026-10-13 |
| 14 | [vllm-project/vllm](https://github.com/vllm-project/vllm) | Apache-2.0 | 面向生产并发和吞吐的模型推理服务。 | 2026-10-25 |
| 15 | [pytorch/pytorch](https://github.com/pytorch/pytorch) | BSD-3-Clause-style | 张量、自动微分和模型训练的长期基础。 | 2026-10-25 |
| 16 | [microsoft/markitdown](https://github.com/microsoft/markitdown) | MIT | 低成本将 Office、HTML、PDF 等转换为 LLM 可消费的 Markdown。 | 2026-10-23 |
| 17 | [openai/openai-agents-python](https://github.com/openai/openai-agents-python) | MIT | 轻量工具调用、handoff、guardrails、MCP 和 tracing。 | 2026-10-22 |
| 18 | [gradio-app/gradio](https://github.com/gradio-app/gradio) | Apache-2.0 | 把 Python 模型函数快速变成可交互验证的演示界面。 | 2026-10-23 |
| 19 | [openai/openai-cookbook](https://github.com/openai/openai-cookbook) | MIT | 学习结构化输出、RAG 和评测的官方实现配方。 | 2026-10-23 |
| 20 | [vibrantlabsai/ragas](https://github.com/vibrantlabsai/ragas) | Apache-2.0 | 量化 RAG 回答质量、上下文相关性和评测集。 | 2026-02-24 |

## Star执行状态

当前账号已确认为hrniux，但GitHub集成对Star状态读取返回403；对Spring AI执行Star写入也返回403 Resource not accessible by integration。因此本任务**未能新增或确认任何Star**，不能从读取失败推断你之前是否收藏过。当前会话没有可操作的已登录本地浏览器。

具备Starring读写权限的本机GitHub CLI可以执行：

```bash
bash scripts/star-ai-repos.sh --dry-run  # 只列出20项，无远端修改
bash scripts/star-ai-repos.sh            # 校验账号hrniux，逐项Star并回读确认
```

脚本不接收或保存Token；复用本机gh已有登录状态。如果仍报403，需要在GitHub连接的授权设置中授予收藏权限，或由本人在浏览器完成收藏。

## 怎样用于你的核保项目

本次交付先增加[一键可验证Demo](VERIFIED_DEMO.md)，保留现有Spring AI/MCP和Mock路由，用固定预期与真实HTTP响应证明业务契约、RAG召回与审计可复现。它没有新增20个运行依赖。

后续优先考虑三条独立改进：用Ollama替换Mock并单独评测生成质量；用Docling处理复杂文档并用TEI替换Hash Embedding；数据增长后用Qdrant替换进程内向量库。每一步先保留现有黄金集并比较Recall/MRR、延迟和失败案例。Spring AI与LangChain4j、Haystack与现有Java编排是替代/互补路线，不必同时引入。

## 许可证与维护的取舍

代码开源不代表模型权重、托管服务或模型API免费；许可证约束也分别适用。PyTorch的API标签为NOASSERTION，但LICENSE正文为BSD三条款结构，所以按正文说明。Ragas当前全名已变为vibrantlabsai/ragas，最近推送停在2026-02-24；因RAG评测仍有参考价值保留在末位，采用前须重查维护情况。

没有将当前采用Elastic License 2.0的Phoenix归入严格开源名单。Langfuse可考虑其MIT开源核心，但企业目录另有商业许可；LlamaIndex当前README声明团队主要精力转向解析产品，因此也未凭历史热度优先入选。

## 逐项依据与限制

### 1. spring-projects/spring-ai

Spring 业务应用的模型、RAG、MCP 集成；与你的核保项目直接相关。

使用边界：2.x 对应 Boot 4.x，按 BOM 对齐版本。

核查来源：[README](https://github.com/spring-projects/spring-ai/blob/main/README.md) · [许可证](https://github.com/spring-projects/spring-ai/blob/main/LICENSE.txt) · [仓库元数据](https://api.github.com/repos/spring-projects/spring-ai) · [发布记录](https://github.com/spring-projects/spring-ai/releases)。

### 2. ollama/ollama

在 Mac 快速启动本地模型 HTTP API。

使用边界：模型权重许可、下载体积和内存另计。

核查来源：[README](https://github.com/ollama/ollama/blob/main/README.md) · [许可证](https://github.com/ollama/ollama/blob/main/LICENSE) · [仓库元数据](https://api.github.com/repos/ollama/ollama) · [发布记录](https://github.com/ollama/ollama/releases)。

### 3. promptfoo/promptfoo

为提示词、模型和 Agent 建立可自动判定的回归评测。

使用边界：云模型及 LLM 判分可能收费。

核查来源：[README](https://github.com/promptfoo/promptfoo/blob/main/README.md) · [许可证](https://github.com/promptfoo/promptfoo/blob/main/LICENSE) · [仓库元数据](https://api.github.com/repos/promptfoo/promptfoo) · [发布记录](https://github.com/promptfoo/promptfoo/releases)。

### 4. langchain-ai/langgraph

实现可持久化、可恢复、支持人工介入的 Agent 流程。

使用边界：简单串行流程可能不需要额外编排框架。

核查来源：[README](https://github.com/langchain-ai/langgraph/blob/main/README.md) · [许可证](https://github.com/langchain-ai/langgraph/blob/main/LICENSE) · [仓库元数据](https://api.github.com/repos/langchain-ai/langgraph) · [发布记录](https://github.com/langchain-ai/langgraph/releases)。

### 5. docling-project/docling

将 PDF 版面、表格、OCR 内容转为 RAG 可用资料。

使用边界：模型许可另查；复杂版面需要验收，CPU 解析可能较慢。

核查来源：[README](https://github.com/docling-project/docling/blob/main/README.md) · [许可证](https://github.com/docling-project/docling/blob/main/LICENSE) · [仓库元数据](https://api.github.com/repos/docling-project/docling) · [发布记录](https://github.com/docling-project/docling/releases)。

### 6. qdrant/qdrant

语义检索、条件过滤与混合搜索的服务化向量数据库。

使用边界：需要独立服务和 embedding；小数据集不一定需要。

核查来源：[README](https://github.com/qdrant/qdrant/blob/master/README.md) · [许可证](https://github.com/qdrant/qdrant/blob/master/LICENSE) · [仓库元数据](https://api.github.com/repos/qdrant/qdrant) · [发布记录](https://github.com/qdrant/qdrant/releases)。

### 7. modelcontextprotocol/java-sdk

用标准协议把 Java 业务能力暴露给多个 AI 客户端。

使用边界：协议 SDK 本身不提供模型和完整业务应用。

核查来源：[README](https://github.com/modelcontextprotocol/java-sdk/blob/main/README.md) · [许可证](https://github.com/modelcontextprotocol/java-sdk/blob/main/LICENSE) · [仓库元数据](https://api.github.com/repos/modelcontextprotocol/java-sdk) · [发布记录](https://github.com/modelcontextprotocol/java-sdk/releases)。

### 8. ggml-org/llama.cpp

GGUF、量化和 Apple Silicon 本地推理的基础组件。

使用边界：模型与上下文长度决定实际内存占用。

核查来源：[README](https://github.com/ggml-org/llama.cpp/blob/master/README.md) · [许可证](https://github.com/ggml-org/llama.cpp/blob/master/LICENSE) · [仓库元数据](https://api.github.com/repos/ggml-org/llama.cpp) · [发布记录](https://github.com/ggml-org/llama.cpp/releases)。

### 9. huggingface/transformers

文本、图像、音频等模型应用与实验的核心生态。

使用边界：模型许可及计算资源需分别评估。

核查来源：[README](https://github.com/huggingface/transformers/blob/main/README.md) · [许可证](https://github.com/huggingface/transformers/blob/main/LICENSE) · [仓库元数据](https://api.github.com/repos/huggingface/transformers) · [发布记录](https://github.com/huggingface/transformers/releases)。

### 10. langchain4j/langchain4j

以 Java 接口、POJO 和注解实现模型调用与工具/RAG集成。

使用边界：与 Spring AI 有重叠，按项目选主框架。

核查来源：[README](https://github.com/langchain4j/langchain4j/blob/main/README.md) · [许可证](https://github.com/langchain4j/langchain4j/blob/main/LICENSE) · [仓库元数据](https://api.github.com/repos/langchain4j/langchain4j) · [发布记录](https://github.com/langchain4j/langchain4j/releases)。

### 11. ml-explore/mlx-lm

Apple Silicon 上的 Python LLM 推理、量化与 LoRA 微调。

使用边界：主要适用于 Apple Silicon，模型规模受统一内存限制。

核查来源：[README](https://github.com/ml-explore/mlx-lm/blob/main/README.md) · [许可证](https://github.com/ml-explore/mlx-lm/blob/main/LICENSE) · [仓库元数据](https://api.github.com/repos/ml-explore/mlx-lm) · [发布记录](https://github.com/ml-explore/mlx-lm/releases)。

### 12. deepset-ai/haystack

可组合、可评估的检索、重排和 RAG 流水线。

使用边界：Python 组件集成，与已有 Java 检索层需明确边界。

核查来源：[README](https://github.com/deepset-ai/haystack/blob/main/README.md) · [许可证](https://github.com/deepset-ai/haystack/blob/main/LICENSE) · [仓库元数据](https://api.github.com/repos/deepset-ai/haystack) · [发布记录](https://github.com/deepset-ai/haystack/releases)。

### 13. huggingface/text-embeddings-inference

独立部署 embedding、rerank 服务，为真实 RAG 替换 Hash Embedding。

使用边界：须选择硬件对应安装路径及模型，额外维护推理服务。

核查来源：[README](https://github.com/huggingface/text-embeddings-inference/blob/main/README.md) · [许可证](https://github.com/huggingface/text-embeddings-inference/blob/main/LICENSE) · [仓库元数据](https://api.github.com/repos/huggingface/text-embeddings-inference) · [发布记录](https://github.com/huggingface/text-embeddings-inference/releases)。

### 14. vllm-project/vllm

面向生产并发和吞吐的模型推理服务。

使用边界：生产优先服务器加速器环境；Mac 首个 Demo 更适合 Ollama/MLX。

核查来源：[README](https://github.com/vllm-project/vllm/blob/main/README.md) · [许可证](https://github.com/vllm-project/vllm/blob/main/LICENSE) · [仓库元数据](https://api.github.com/repos/vllm-project/vllm) · [发布记录](https://github.com/vllm-project/vllm/releases)。

### 15. pytorch/pytorch

张量、自动微分和模型训练的长期基础。

使用边界：应用项目未必需要直接依赖；API标签NOASSERTION，以LICENSE正文为准。

核查来源：[README](https://github.com/pytorch/pytorch/blob/main/README.md) · [许可证](https://github.com/pytorch/pytorch/blob/main/LICENSE) · [仓库元数据](https://api.github.com/repos/pytorch/pytorch) · [发布记录](https://github.com/pytorch/pytorch/releases)。

### 16. microsoft/markitdown

低成本将 Office、HTML、PDF 等转换为 LLM 可消费的 Markdown。

使用边界：不保证复杂版面和扫描件的高保真；视觉/云服务可选且可能收费。

核查来源：[README](https://github.com/microsoft/markitdown/blob/main/README.md) · [许可证](https://github.com/microsoft/markitdown/blob/main/LICENSE) · [仓库元数据](https://api.github.com/repos/microsoft/markitdown) · [发布记录](https://github.com/microsoft/markitdown/releases)。

### 17. openai/openai-agents-python

轻量工具调用、handoff、guardrails、MCP 和 tracing。

使用边界：默认教程通常需要模型 API；服务费用与 SDK 开源分开。

核查来源：[README](https://github.com/openai/openai-agents-python/blob/main/README.md) · [许可证](https://github.com/openai/openai-agents-python/blob/main/LICENSE) · [仓库元数据](https://api.github.com/repos/openai/openai-agents-python) · [发布记录](https://github.com/openai/openai-agents-python/releases)。

### 18. gradio-app/gradio

把 Python 模型函数快速变成可交互验证的演示界面。

使用边界：UI 开源不代表后端模型免费；公网分享需显式开启。

核查来源：[README](https://github.com/gradio-app/gradio/blob/main/README.md) · [许可证](https://github.com/gradio-app/gradio/blob/main/LICENSE) · [仓库元数据](https://api.github.com/repos/gradio-app/gradio) · [发布记录](https://github.com/gradio-app/gradio/releases)。

### 19. openai/openai-cookbook

学习结构化输出、RAG 和评测的官方实现配方。

使用边界：多数示例需要 API Key，需按当前 SDK/模型检查兼容。

核查来源：[README](https://github.com/openai/openai-cookbook/blob/main/README.md) · [许可证](https://github.com/openai/openai-cookbook/blob/main/LICENSE) · [仓库元数据](https://api.github.com/repos/openai/openai-cookbook) · [发布记录](https://github.com/openai/openai-cookbook/releases)。

### 20. vibrantlabsai/ragas

量化 RAG 回答质量、上下文相关性和评测集。

使用边界：旧地址explodinggradients/ragas已重定向；截至核查最近推送2026-02-24，维护活跃度较低。

核查来源：[README](https://github.com/vibrantlabsai/ragas/blob/main/README.md) · [许可证](https://github.com/vibrantlabsai/ragas/blob/main/LICENSE) · [仓库元数据](https://api.github.com/repos/vibrantlabsai/ragas) · [发布记录](https://github.com/vibrantlabsai/ragas/releases)。
