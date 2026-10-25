# 管理资源 Location 完整性修复

实测 Knowledge/Prompt 管理 API 在保存包含空格或百分号的标识后，用原始字符串构建 URI 抛异常，响应400但记录已创建；问号与井号会产生201但无法追随的 Location。Prompt 的 Location 还指向尚不存在的版本详情 GET。主代理已批准把这一已证明的正确性问题纳入本轮。

对受影响的 Knowledge 草稿/新版本和 Prompt 新版本响应，使用 Spring UriComponentsBuilder 的路径模板变量严格编码动态段，保留空格、中文、问号、井号、百分号等合法标识的原值。知识和 Prompt 标识在写入前拒绝斜杠与反斜杠，因为容器拒绝编码后的路径分隔符；同时拒绝点段 `.`/`..` 和内部控制字符，避免浏览器路径规范化或容器拒绝造成不可寻址记录。不给中文和内部空格增加 slug 限制。

新增 `GET /api/v1/prompts/{code}/versions/{version}`，通过现有 PromptTemplateRepository 读取确定版本，复用 canonical code 和 `PROMPT_VERSION_NOT_FOUND` 错误。原版本集合、激活和预览接口保持原行为。

TDD 验证创建201后的 Location 可真实 GET 到同一标识/版本；知识发布后创建 v2 的 Location 同样可追随；Prompt 的原集合仍按版本返回。非法分隔符请求/直接服务调用不增加任何仓储记录。MockMvc 后追加真实 servlet 容器 HTTP 编码往返验证，以避免测试工具代替容器解码而掩盖问题。
