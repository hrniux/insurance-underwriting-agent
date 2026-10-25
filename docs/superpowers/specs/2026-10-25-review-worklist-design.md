# 服务端人工复核案件列表

现有演示台只恢复当前标签页的会话记录；即使评估与人工复核已持久化，换标签页或重启后也无法在页面发现历史案件。本次增加只读的服务端案件列表，把已保存评估与不可覆盖的人工复核关联起来，支持筛选、分页和打开原评估。

## 方案与边界

采用独立查询服务组合现有 EvaluationRepository/HumanReviewRepository。相比浏览器下载两份完整列表再关联，这保持筛选、状态和顺序的一致性；相比新增工作流表，它无需改变持久化和不可覆盖复核约束。用户已授权自主推进，主代理批准这一限定范围。

`GET /api/v1/underwriting/review-worklist` 接收可选 `status`、`decision`、`policyNo`，以及 `page`（默认0，非负）、`size`（默认10，1..50）。保单筛选为 trim 后忽略大小写的精确匹配，最长64字符。状态为 UNREVIEWED（无人工记录）、INFORMATION_REQUESTED（MORE_INFORMATION_REQUIRED）、REVIEWED（APPROVED/REJECTED）。结果先筛选，再按 riskScore 降序、createdAt 升序、id 升序稳定排序，最后分页。响应为 items/page/size/totalElements/totalPages；每项只含 id、policyNo、Agent decision/riskLevel/riskScore、createdAt、status、review outcome/reviewerId/reviewedAt 和 replayedFromEvaluationId，不返回完整证据或长审计正文。列表查询不调用模型、不创建会话或评估、不写复核。

演示台提供服务端案件区域，默认筛选未复核且需人工复核的评估，支持刷新、保单/状态/Agent结论筛选和前后页。打开时从服务端读取原评估与人工复核，再复用现有结果、报告、重演和复核界面；不依赖 sessionStorage，不重新核保。已请求资料的记录保持不可覆盖，可通过现有重演取得新评估继续处置。

加载中声明 aria-busy/aria-live；加载失败隐藏旧列表，防止把过时结果当作新筛选结果；无数据展示明确空状态；新查询或打开案件时取消旧请求，避免慢响应覆盖新状态。提交人工复核成功后刷新服务端列表。

不增加指派、领取、SLA、可编辑复核、工作流引擎、外部依赖或数据库表。不改变 Agent 判断、知识检索、重演、报告、默认离线启动和当前浏览器会话历史。默认内存与 persistent-demo 通过同一仓储端口工作；本地演示规模使用两次 findAll 的线性关联，生产大规模数据应改为数据库侧查询分页。

验证包括真实 REST 工作流、派生状态、确定性排序、筛选后分页、非法参数、无写入副作用、persistent-demo 仓储发现和新页面打开已保存案件。保留现有完整 Maven verify。
