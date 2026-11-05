# SQLancer (GaussDB 深度扩展版)

[![Build Status](https://github.com/sqlancer/sqlancer/workflows/ci/badge.svg)](https://github.com/sqlancer/sqlancer/actions)
[English Documentation](README.md) | [中文文档](README_CN.md)

![SQLancer](media/logo/png/sqlancer_logo_logo_pos_500.png)

**SQLancer** 是用于自动化测试数据库管理系统（DBMS）并挖掘其内核实现缺陷的顶级模糊测试工具。与传统的语法测试工具不同，SQLancer 的核心目标是发现数据库内核自身的代码逻辑缺陷（如优化器算错、执行器漏查数据、规划器崩溃、类型转换异常等）。

本仓库为针对 **华为 GaussDB (GaussDB Kernel 507.0.0+)** 进行深度定制的专用版本，支持 Oracle 兼容模式、ASTORE/USTORE/CSTORE 三存储引擎、跨分区行迁移、SMP 并行计算、并发事务隔离性验证以及包含 **30 大国际顶会变异测试机（Oracles）** 的全量测试矩阵。

---

## 核心特性与学术成果

本分支融合了学术界顶会论文成果（POPL、OOPSLA、SIGMOD、VLDB、ICSE、ASE、USENIX ATC、ISSTA、OSDI 等），并针对 GaussDB 的企业级架构进行了专有增强：

### 1. 全量 30-Oracle 综合测试矩阵
| 测试机 (Oracle) | 对应学术论文 | 检测机理与核心优势 |
| :--- | :--- | :--- |
| **CODDTest** | SIGMOD 2025 | 基于关系完备性与会话级常数优化驱动测试，擅长挖掘复杂标量子查询提升与唯一性消除缺陷。 |
| **TLP** (三值逻辑分区) | POPL / OOPSLA 2020 | 将查询谓词划分为 $P$, $\neg P$, $P \text{ IS NULL}$，验证三值逻辑并集等价性。 |
| **NoREC** (无优化参考引擎) | ESEC/FSE 2020 | 对比优化过滤查询与全表投影旁路求值查询，高灵敏度捕获谓词化简与索引误过滤。 |
| **DQP / DQE** (差异执行计划) | SIGMOD 2024 / VLDB | 通过 GUC / Hint 强行切换物理算子路径（如 SeqScan vs IndexOnlyScan vs SkipScan），捕获执行器分歧。 |
| **EET** (等价表达式变换) | ACM TOSEM / ICSE 2023 | 通过构造重言式条件与代数恒等式替换，检验表达式求值器与隐式类型转换一致性。 |
| **THANOS** (存储引擎对齐) | ICSE 2025 / FSE 2021 | 跨存储引擎差异比对（ASTORE 堆存储 vs USTORE 原地更新引擎 vs CSTORE 列式存储与 Psort 索引），捕获精度与可见性分歧。 |
| **ERIQ** (中间查询等价表示) | arXiv 2026 | 将中间查询在 Derived Table、CTE (WITH)、视图 (VIEW) 与临时表 (TEMP TABLE) 4种等价形式间轮转比对，深挖谓词下推与物化分歧。 |
| **SRS** (集合论蕴含推理) | SIGMOD 2026 | 基于集合论包含（INNER $\subseteq$ LEFT）与互斥完备划分（SEMI $\cap$ ANTI = $\emptyset$ 且 SEMI $\cup$ ANTI = BASE），专攻优化器 Join 树消除与子查询提升。 |
| **ARG** (抽象重写规则差分) | ASE 2025 | 深度覆盖 GaussDB 全部 28 项专有代数重写规则（rewrite_rule），精确定位 Query Rewriter、CSE 与子连接拉升漏洞。 |
| **CERT** (基数估计单调性) | ICSE 2024 | 约束条件下的 CBO 基数估计单调性检验（$Q_1 \land P_2 \implies \text{Rows}(Q_2) \le \text{Rows}(Q_1)$），专攻优化器代价失真与错误执行计划缺陷。 |
| **DDLCheck** (等价模式演化) | VLDB 2025 | 对比经过动态 DDL 演化（ALTER TABLE ADD/DROP/RENAME COLUMN, INDEX, VACUUM）的表与直接静态建表的物理行解码与系统缓存一致性。 |
| **EDC** (等价数据变换域移) | SIGMOD 2026 | 基于仿射数据变换（$x' = x + K$）验证谓词选择率同构与聚集同态，捕获数值算术溢出与 B-Tree 边界扫描缺陷。 |
| **WSS** (写专有可串行化并发) | VLDB 2025 | 双线程并发事务在共享行上执行非对易更新，验证可串行化与可重复读隔离级别下的最终状态等价性，捕获丢失更新与脏写异常。 |
| **JIT** (LLVM编译差分测试) | SIGMOD / VLDB 体系 | 强制比对同一查询在解释执行 (`SET jit = off`) 与全量 LLVM JIT 动态编译 (`SET jit = on, jit_above_cost = 0`) 下的结果，专攻汇编指令生成与寄存器溢出。 |
| **BOUNDARY** (函数极值边界测试) | EuroSys 2025 | 针对 GaussDB A 模式独有的数百个 Oracle 兼容函数，系统化注入 NaN、-0.0、INT_MIN/MAX、空串与格式掩码等极值参数，检验内核鲁棒性与异常规约。 |
| **VALSCOPE** (值语义近似微扰) | OSDI 2026 (Best Paper) | 基于统一近似与值微扰模型（$V' = f(V, \delta)$），校验聚合同态（$SUM(c+K) = SUM(c) + N \cdot K$）、窗口排序单调性与标量代数回环，专攻行数正确但数据算错的值语义 Bug。 |
| **PLSQL** (过程化程序测试) | EuroSys / PLDI 前沿 | 业界首个针对 GaussDB A 模式 Oracle PL/SQL 过程化语言的测试机，覆盖显式游标循环、动态 SQL（EXECUTE IMMEDIATE）、IF-ELSE 分支，比对过程化与声明式聚合一致性。 |
| **PINOLO** (子集超集蕴含推理) | USENIX ATC 2023 | 打破传统精准等价限制，建立子集/超集蕴含链（$R(Q_{sub}) \subseteq R(Q_{base}) \subseteq R(Q_{super})$）与基数单调性，专门检测优化器误过滤与复杂谓词合并缺陷。 |
| **JIR** (连接蕴含推理) | SIGMOD 2026 | 基于一阶逻辑在多表连接上的蕴含规律，验证外连接空值拒绝降级（Null-Rejecting Outer-to-Inner）、等值连接传递闭包及自连接唯一消除。 |
| **SCOR** (短路求值与非平衡表达式) | SIGMOD 2026 | 构造受保护的危险运算表达式（除零、无效类型转换），检测优化器代价评估与谓词重排是否破坏了 SQL 规定的 AND/OR/CASE 短路语义。 |
| **PUPPY** (约束优化空间探索) | ICSE 2025 | 强制调控并关闭特定物理算子（HashJoin, MergeJoin, SeqScan, IndexScan, Materialize），比对不同执行路径的结果集一致性，捕获代价模型倒挂与隐藏算子 Bug。 |
| **DDLUMOS** (事务性 DDL 回滚保真) | USENIX ATC 2024 | 针对 GaussDB 特有的事务性 DDL 能力，在事务中交织执行 DDL 与 DML 并执行 ROLLBACK，验证数据元组与系统目录（pg_class/pg_attribute）无损还原。 |
| **SQLASER** (复杂子句链式组合) | USENIX Sec / arXiv | 针对高阶复杂 SQL 子句链（Grouping Sets, Rollup, Window Frames, CTEs），验证高维聚合与阶梯式分解执行的等价性，专攻执行器内存溢出与分组展开缺陷。 |
| **ISOREL** (关系感知隔离性检测) | ISSTA 2025 | 系统化探测 ANSI/GaussDB 隔离级别（RC/RR/Serializable）下的不可重复读（P2）、幻读（P3）与写偏斜（A5B），专攻快照隔离泄漏与多版本读穿透。 |
| **TXCHECK** (依赖图环路冲突分析) | OSDI 2023 | 构建多事务读写直接依赖有向图（Direct Dependency Graph, DDG），检测 $rw \to rw$ 成环与因果读偏斜，验证可串行化快照（SSI）锁管理正确性。 |
| **FUCCI** (冲突不变量引导测试) | VLDB 2025 | 基于细粒度行级锁与屏障步进同步，验证排他更新锁在 COMMIT 与 ROLLBACK 下的严格状态守恒，捕获并发更新锁丢失与未提交脏写。 |
| **SEDAR** (并发调度与时序抖动) | IEEE TSE 2024 | 引入动态线程时序扰动延迟（$\Delta t \in \{0, 10\text{ms}, 50\text{ms}, 100\text{ms}\}$），专攻多行转账与记账一致性守恒（$\sum \text{balance} = \text{const}$）。 |
| **DISTSQL** (集中式 vs 分布式双模差分) | SIGMOD 2026 | 比对单节点串行计划与多分片分布式流式计划（Two-Phase Partial/Final Aggregation, Stream Redistribute/Broadcast Join, SMP query_dop &gt; 1）的结果集一致性，捕获分布式网络分发与跨节点数据丢失。 |
| **IVM** (物化视图与增量刷新一致性) | VLDB / SIGMOD 2025 | 灵感源自 BuzzHouse / IVM Testing，针对物化视图及其增量更新机制，在交织 DML 变更与 REFRESH 后检验物化视图物理元组多重集与直接执行基表子查询的精准等价性及外层谓词下推守恒。 |
| **EPSC** (等价计划状态检验) | SIGMOD 2020 | 事务与会话状态转换一致性探测。 |

### 2. GaussDB 企业级特性专属支持
* **Oracle 模式深度兼容**：全面覆盖 `sql_compatibility = 'A'` 模式下的 Oracle 专有函数（`NVL2`, `LNNVL`, `DECODE`, `ROWNUM`）及语法。
* **双存储引擎支持**：支持 `WITH (storage_type = astore)`（传统追加写）与 `WITH (storage_type = ustore)`（In-place 原地更新与 UNDO 回滚段）。
* **高级分区表与动态迁移**：支持 Range / Hash 分区，强制覆盖 `ENABLE ROW MOVEMENT`（跨分区 UPDATE 自动迁移）与局部/全局索引并发更新。
* **SMP 多线程并行查询**：动态调控 `SET query_dop = 1/2/4`，高频压迫 Parallel SeqScan、Parallel HashJoin 与 Parallel WindowAgg。
* **长稳运行自愈保障**：集成会话级 `DISCARD ALL`、`plancache_clean()` 与系统缓存自愈重试，彻底解决长时间压测下的 `memory is temporarily unavailable` 内存池耗尽问题。
* **已知缺陷智能旁路**：提供 `--bypass-boolean-test-range-bug` 开关，智能识别并静默过滤已提单的 3VL 常数折叠问题，避免已知 Bug 刷屏阻碍新 Bug 挖掘。

---

## 快速上手

### 环境要求
* **Java**：JDK 11 或更高版本
* **Maven**：3.6+
* **GaussDB 目标实例**：GaussDB 507.0.0+ 或 openGauss（开启相应端口与测试账号）

### 1. 编译构建
```bash
git clone https://github.com/yuziyydss/sqlancer.git
cd sqlancer
git checkout gaussdb-coddtest

# 编译生成可执行 Jar 包
mvn package -DskipTests
```
编译成功后，产物位于 `target/sqlancer-2.0.0.jar`。

### 2. 运行测试

#### 场景 A：全量 31-Oracle 综合压测（推荐）
```bash
java -Xmx4096m -jar target/sqlancer-2.0.0.jar \
  --username sqlancer \
  --password Sqlancer_234 \
  --host 127.0.0.1 \
  --port 19995 \
  --num-threads 3 \
  --timeout-seconds 7200 \
  --num-queries 1200 \
  --num-tries 10000 \
  --print-progress-summary true \
  gaussdb \
  --oracle EET,CODDTEST,DQP,DQE,THANOS,EPSC,TLP,NOREC,ERIQ,SRS,ARG,CERT,DDLCHECK,EDC,WSS,JIT,BOUNDARY,VALSCOPE,PLSQL,PINOLO,JIR,SCOR,PUPPY,DDLUMOS,SQLASER,ISOREL,TXCHECK,FUCCI,SEDAR,DISTSQL,IVM \
  --bypass-boolean-test-range-bug true
```

#### 场景 B：快速单 Oracle 定向测试 (例如 NoREC 或 DQP)
```bash
# 测试 NoREC 优化器逻辑缺陷
java -jar target/sqlancer-2.0.0.jar --num-threads 2 gaussdb --oracle NOREC

# 测试 DQP 执行计划分歧（如 Index Skip Scan）
java -jar target/sqlancer-2.0.0.jar --num-threads 2 gaussdb --oracle DQP
```

---

## 参数速查表

### 全局通用参数 (置于 `gaussdb` 之前)
* `--host`：数据库服务器 IP（默认 `127.0.0.1`）。
* `--port`：数据库端口（例如 GaussDB 常用 `19995` 或 `5432`）。
* `--username` / `--password`：连接凭据。
* `--num-threads`：测试并发工作线程数（建议 2~4）。
* `--timeout-seconds`：全局最大执行时长（秒），超时后自动保存并优雅退出。
* `--num-queries`：每个随机数据库实例执行的验证查询条数。
* `--num-tries`：尝试的数据库轮数上限。
* `--print-progress-summary`：是否定时打印 QPS 吞吐与进度汇总。

### GaussDB 专有参数 (置于 `gaussdb` 之后)
* `--oracle`：启用的测试机列表，逗号分隔，可选：`EET`, `CODDTEST`, `DQP`, `DQE`, `THANOS`, `EPSC`, `TLP`, `NOREC`, `ERIQ`, `SRS`, `ARG`。
* `--bypass-boolean-test-range-bug`：设置为 `true` 时，自动跳过已知的 `BETWEEN SYMMETRIC` 优化折叠与 3VL 范围化简已知缺陷。
* `--connection-url`：直接指定特定 JDBC 连接串。

---

## 日志查看与复现用例提取

压测运行期间，所有日志默认输出至 `target/logs/gaussdb/`（或安装目录下的 `logs/gaussdb/`）：
* **`database<id>-cur.log`**：当前实例正在执行的所有 DDL、DML 和测试查询流。
* **`database<id>.log`**：当 SQLancer 捕获到结果不一致（`AssertionError`）或非预期崩溃时，会自动生成独立的缺陷日志。

### 缺陷复现日志示例：
日志开头会直接提供精炼的触发信息，随后附带全套建表与数据回放脚本：
```text
-- java.lang.AssertionError: DQP plan mismatch on profile nestloop_index_no_opfusion
-- BaselineQuery: SELECT COUNT(*) FROM t0 WHERE c0 IS NOT NULL
-- VariantQuery:  SELECT /*+ IndexScan(t0) */ COUNT(*) FROM t0 WHERE c0 IS NOT NULL
-- BaselineResult: [[5]]
-- VariantResult:  [[3]]
...（完整的可直接回放的 SQL 语句）
```
开发人员只需将该日志内的 SQL 复制进 `gsql` 即可 100% 稳定复现现场。

---

## 贡献与致谢

本分支基于开源项目 [SQLancer](https://github.com/sqlancer/sqlancer) 进行二次研发与扩展，致谢 Manuel Rigger 团队及开源社区在数据库系统测试领域做出的卓越开创性工作。
