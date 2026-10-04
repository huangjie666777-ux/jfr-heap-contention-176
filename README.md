# heapx — Java 堆保留内存分析后端

定位 Java 堆中保留大块内存的对象。纯后端：Java 17 + Javalin 6.5.0，
HPROF 读取使用 NetBeans Profiler 库（RELEASE230，仅用于读取对象与浅堆字节），
图构建、支配分析与保留量计算均为自研实现。

## 构建与启动

```bash
mvn -B package          # 编译 + 跑测试 + 生成可执行 jar
java -jar target/heapx-1.0.0-jar-with-dependencies.jar
# 默认端口 7070，可用 PORT 环境变量覆盖：PORT=7717 java -jar ...
```

## 分析口径

- **节点**：普通实例与数组（对象数组、原始数组）。不模拟类加载器。
- **根**：转储中的 GC 根（线程栈、JNI、监视器等）以及类静态引用指向的对象。
  分析时加入一个虚拟根，指向所有根对象。
- **边**：实例引用字段（含继承字段，标签为 `声明类#字段名`）与对象数组元素
  （标签为 `[下标]`）。排除 `java.lang.ref.Reference` 声明的 `referent`
  （软/弱/虚引用不算强引用），忽略 null 引用。
- **支配**：对象 d 支配 o 当且仅当从虚拟根到 o 的每条路径都经过 d。
  立即支配者用 Cooper–Harvey–Kennedy 迭代算法在逆后序上计算，
  正确处理环、共享子图与多个根。
- **保留字节**：支配子树中所有对象的浅堆字节之和（不是可达总和）。
  不可达对象单独标记（`unreachable: true`），不参与保留排行。
- **浅堆字节**：取自 NetBeans 读取器的 `Instance.getSize()`；保留量不使用
  任何库计算结果。
- **对象 ID**：HPROF 原始对象 ID 的十六进制字符串（`0x` 前缀），无损传递；
  查询时带不带 `0x` 前缀均可。

## 限制

- 文件 ≤ 100 MiB（超限 422，请求体超限 413）
- 对象 ≤ 50,000（`HEAPX_MAX_OBJECTS` 可调）
- 边 ≤ 200,000（`HEAPX_MAX_EDGES` 可调）
- JFR 录制 ≤ 100 MiB；`jdk.JavaMonitorEnter` 争用事件 ≤ 100,000 条（超限 422）
- 损坏文件返回 400；任何失败都不会发布半份分析。
- 每次上传得到独立 `analysisId`，并发上传/查询互不影响；
  `DELETE` 释放全部资源，无持久化。

## API

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/analyses` | 上传 HPROF（multipart 字段 `file` 或原始请求体），返回 `analysisId` |
| GET | `/api/analyses/{id}` | 分析概要（对象/边/不可达数） |
| GET | `/api/analyses/{id}/retained?limit=50&offset=0` | 按保留字节降序的对象列表 |
| GET | `/api/analyses/{id}/objects/{hexId}` | 单个对象：类、浅堆、保留量、立即支配者 |
| GET | `/api/analyses/{id}/objects/{hexId}/path` | 到任一根的最短强引用路径（逐边字段/下标） |
| POST | `/api/analyses/{id}/cut-plan` | 最低代价断引用规划：使目标对象全部不可达的最小代价引用集合 |
| POST | `/api/analyses/{id}/scans` | 提交敏感值规则并扫描 byte[]/char[] 载荷，返回不可变 scanId 与全部命中 |
| GET | `/api/analyses/{id}/scans/{scanId}` | 按 scanId 读取扫描结果（不可变，可重复查询） |
| GET | `/api/analyses/{id}/scans/{scanId}/export` | 下载遮除后的真实 HPROF 副本（命中区间并集置零） |
| POST | `/api/analyses/{id}/recordings` | 上传同一 JVM 的真实 JFR（multipart 字段 `file` 或原始请求体），返回 `recordingId` |
| GET | `/api/analyses/{id}/recordings/{recordingId}` | 录制概要（事件数、起止、线程索引统计） |
| GET | `/api/analyses/{id}/recordings/{recordingId}/contention?from=...&to=...` | ISO-8601 UTC 左闭右开窗口查询：相交事件、裁剪时长、按线程/锁类聚合 |
| DELETE | `/api/analyses/{id}` | 删除分析并释放资源（源转储、扫描结果一并清理） |

## JFR 锁争用联查

在同一分析（analysisId）上上传**同一 JVM** 的真实 JFR 录制文件，用
`jdk.jfr.consumer` 读取 `jdk.JavaMonitorEnter` 事件（其余事件类型一律忽略），
与堆证据联查卡顿来源。

- **上传**：POST `/api/analyses/{id}/recordings`，multipart 字段 `file` 或原始
  请求体；只接受真实录制文件，**JSON 元数据不能替代录制**（`application/json`
  一律 400）。成功返回 `recordingId`、事件数、首事件开始与末事件结束。
- **事件口径**：每条事件保存开始/结束（UTC）、Java 线程 ID
  （`RecordedThread.getJavaThreadId()`）、锁类（`monitorClass`）与栈帧
  （最多 64 帧）。
- **线程关联**：从 HPROF 的 `java.lang.Thread`（含子类）实例读取
  `java.lang.Thread` 声明的 long 字段 `tid`，与 JFR 的 Java 线程 ID 精确
  关联——绝不使用线程名、OS 线程 ID 或监视器地址。同一 tid 被多个 Thread
  对象声明 → `ambiguous`；无对应 Thread 对象 → `unmatched`；Thread 对象缺
  `tid` 字段计入 `missingTid`。未匹配/歧义事件全部保留，只是不附堆证据。
- **窗口查询**：GET `.../contention?from=<ISO8601 UTC>&to=<ISO8601 UTC>`，
  左闭右开 `[from, to)`。返回与窗口相交的事件及**裁剪后**时长
  （`clippedStart/clippedEnd/clippedNanos`），并按线程汇总：事件数、等待区间
  **并集**纳秒数、按锁类分组的并集时长。先裁剪再合并，同线程重叠区间不重复
  计数。`eventLimit`（默认 1000）只截断事件清单，聚合始终基于全部相交事件。
- **堆证据**：已匹配线程附堆对象 ID、原保留字节（支配子树口径，与
  `/retained` 一致）与最短根路径；不可达线程对象标记 `unreachable: true`
  且不给保留值。
- **解释边界**：结果仅反映**已录制**的争用，不断言死锁或内存泄漏；JFR 与堆
  转储采集时刻不同，不视为同时快照。

限制与生命周期：

- JFR 文件 ≤ 100 MiB（超限 422，请求体超限 413）；争用事件 ≤ 100,000 条（超限 422）。
- 损坏或非 JFR 文件 400；任何失败都不发布录制结果；录制按 analysisId 隔离。
- 删除分析会一并清理其全部录制、源转储与 NetBeans 读取器留下的
  `<dump>.nbcache` 缓存目录（此前版本删除分析时该目录会残留）。

## 断引用规划（cut-plan）

请求体（JSON）：

```json
{
  "targets": ["0x7090176a8", "0x7090276b8"],
  "candidates": [
    {"source": "0x709017690", "via": "java.util.ArrayList#elementData",
     "target": "0x7090676f8", "cost": 15},
    {"source": "0x7090676f8", "via": "[0]", "target": "0x7090176a8", "cost": 10}
  ]
}
```

- `targets`：要释放的对象 ID（十六进制，1–32 个，必须存在）。
- `candidates`：允许断开的强引用清单（≤1000 条）。`via` 与图查询返回的引用
  标签一致（`声明类#字段名` 或 `[下标]`）；`cost` 为 1–1,000,000,000 的整数。
  源/目标对象必须存在、引用必须真实存在；重复候选、非法代价、不存在的引用
  一律 400 拒绝。
- 求解口径：所有 GC 根与静态引用目标是固定根，仅候选中的引用可断开。
  在所有目标上做**一次**最小 s-t 割（超源→各根、各目标→超汇，不可断边容量
  无穷），精确给出使全部目标不可达的最小总代价集合——共享前缀只付一次，
  环、多根、同对象间不同字段都联合优化，等价最优解返回任意一个。
  已不可达的目标不产生费用。

成功响应（200）：

```json
{
  "feasible": true,
  "totalCost": 15,
  "cuts": [{"source": "0x709017690", "via": "java.util.ArrayList#elementData",
            "target": "0x7090676f8", "cost": 15}],
  "newlyUnreachable": {"count": 21, "shallowBytes": 1311400, "ids": ["0x..."]},
  "alreadyUnreachableTargets": []
}
```

`newlyUnreachable` 是断开前后根可达集合之差（新增不可达对象 ID、数量、
浅堆字节总和），原本不可达的对象不计入，也不使用任何旧保留值累加。

无解响应（422）：附一条完全由不可断引用组成的根→目标证据路径；
目标本身是根时 `targetIsRoot: true` 且路径为空。

```json
{
  "feasible": false,
  "error": "target stays reachable through references that are not in the cuttable list",
  "evidence": {"target": "0x7090176a8", "targetIsRoot": false,
               "uncuttablePath": [{"from": "0x709017690", "via": "...", "to": "0x..."}]}
}
```

规划是只读操作：不改变原图、保留排名与路径查询；请求间完全隔离；
分析删除后再规划返回 404。

## 敏感值扫描与遮除导出

提交规则（POST `/api/analyses/{id}/scans`）：

```json
{"rules":[
  {"id":"token-1","text":"SECRET-VALUE-1234"},
  {"id":"pin-2","text":"ABCD1234"}]}
```

- 规则 1–16 条；`id` 为 1–64 字符可打印 ASCII 且全请求唯一；`text` 为 4–128
  字符可打印 ASCII（0x20–0x7E）。重复 id、重复原文、非法字符一律 400 拒绝，
  失败不发布任何扫描结果。
- 响应与日志只含规则 id，绝不回显原文。

扫描口径与边界：

- 只扫描 `byte[]` 与 `char[]` 的载荷：byte[] 按 ASCII 字节精确匹配，
  char[] 按字符精确匹配（高字节为零的 ASCII 字符）。不识别 UTF-8/UTF-16
  等其他编码，不匹配基本类型数组之外的记录。
- 枚举全部位置：重叠命中、多条规则命中同一数组、不可达数组全部列出；
  匹配不跨数组。
- 每个命中给出 `ruleId`、`arrayId`、`arrayType`、`elementStart`（元素起点）、
  `length`（元素长度）、`reachable`；可达命中附原字段根路径（逐边
  `声明类#字段名` / `[下标]`），与 `/path` 查询同源。
- 结果绑定不可变 `scanId`，可重复 GET；兼容 4/8 字节对象 ID 与分段堆
  （HEAP_DUMP_SEGMENT）。

遮除导出（GET `.../scans/{scanId}/export`）：

- 返回原转储的真实副本（`application/octet-stream`），全部命中区间的并集
  在数组载荷内置零，未命中载荷与其余记录逐字节保留——不做全文件字符串
  替换，对象 ID、GC 根、引用、类型、长度、类名与其他内容完全不变。
- 共享数组只改一次，多个持有者读到同一遮除值；响应头 `X-Masked-Arrays`
  为去重后的修改数组数，`X-Masked-Bytes` 为实际改写字节数（重叠区间
  合并计算，不累加）。
- 副本可重新上传分析：对象图、保留排名、根路径与断引用规划口径不变。
  注意：类名、字段名与其他未命中内容仍保留，这不是完全匿名化。
- 原转储在分析存续期间保留用于扫描与导出；`DELETE` 分析会删除源文件、
  全部扫描结果与导出临时资源，随后对该分析的一切请求返回 404。

```bash
# 扫描
curl -s -X POST "http://localhost:7717/api/analyses/<analysisId>/scans"   -H 'Content-Type: application/json'   -d '{"rules":[{"id":"token-1","text":"SECRET-VALUE-1234"}]}'
# => {"scanId":"...","ruleCount":1,"hitCount":2,"hits":[{"ruleId":"token-1",
#     "arrayId":"0x...","arrayType":"byte[]","elementStart":2,"length":17,
#     "reachable":true,"path":{...}}, ...]}

# 下载遮除副本并查看计数
curl -s -D - -o masked.hprof   "http://localhost:7717/api/analyses/<analysisId>/scans/<scanId>/export"
# => X-Masked-Arrays: 2 / X-Masked-Bytes: 34

# 副本重新上传分析，排名与路径口径不变
curl -s http://localhost:7717/api/analyses -F "file=@masked.hprof"
```

## curl 演示（真实堆转储）

```bash
# 制造一个真实堆转储
cat > Leaky.java <<'J'
import java.util.*;
public class Leaky {
  static List<byte[]> cache = new ArrayList<>();
  public static void main(String[] a) throws Exception {
    for (int i = 0; i < 20; i++) cache.add(new byte[64 * 1024]);
    Thread.sleep(120_000);
  }
}
J
javac Leaky.java && java Leaky &
jmap -dump:live,format=b,file=real.hprof $!

# 上传（4.7 MiB / 24,066 对象 / 36,691 边）
curl -s http://localhost:7717/api/analyses -F "file=@real.hprof"
# => {"analysisId":"001c721c-...","objects":24066,"edges":36691,"unreachableObjects":207}

# 保留排行：Leaky.cache 的 ArrayList 保留 1.31 MB（20 个 64 KiB 字节数组）
curl -s "http://localhost:7717/api/analyses/<analysisId>/retained?limit=5"

# 根路径：ArrayList --elementData--> Object[] --[0]--> byte[]
curl -s "http://localhost:7717/api/analyses/<analysisId>/objects/0xfc01ded0/path"

# 断引用规划：断开 elementData（代价 15）一次释放整个缓存
curl -s -X POST "http://localhost:7717/api/analyses/<analysisId>/cut-plan" \
  -H 'Content-Type: application/json' \
  -d '{"targets":["0xfc01ded0"],"candidates":[
        {"source":"0x...","via":"java.util.ArrayList#elementData","target":"0x...","cost":15},
        {"source":"0x...","via":"[0]","target":"0xfc01ded0","cost":10}]}'
# => {"feasible":true,"totalCost":15,"cuts":[...elementData...],
#     "newlyUnreachable":{"count":21,"shallowBytes":1311400,...}}

# 释放
curl -s -X DELETE "http://localhost:7717/api/analyses/<analysisId>"
```

## curl 演示（真实 JFR + HPROF 联查）

```bash
# 同一 JVM 制造真实锁争用并同时采集 JFR 与堆转储
cd demo && javac ContentionDemo.java
java -XX:StartFlightRecording=filename=contention.jfr,settings=profile ContentionDemo &
PID=$!   # 4 个 worker 在共享监视器上争用约 1 秒，随后 JVM 驻留
sleep 10
jcmd $PID JFR.dump filename=contention.jfr
jmap -dump:live,format=b,file=contention.hprof $PID

# 上传堆转储（真实 JVM 活动对象可能超过默认 5 万对象上限，
# 可用 HEAPX_MAX_OBJECTS=150000 启动服务，或使用 -dump:live 缩小堆）
curl -s http://localhost:7717/api/analyses -F "file=@contention.hprof"
# => {"analysisId":"d106b5bb-...","objects":73125,"edges":121376,"unreachableObjects":7296}

# 上传同一 JVM 的 JFR（multipart 或原始请求体；JSON 一律 400）
curl -s http://localhost:7717/api/analyses/<analysisId>/recordings -F "file=@contention.jfr"
# => {"recordingId":"6a0e1e6b-...","events":81,
#     "firstEventStart":"2026-10-04T14:47:27.703730596Z",
#     "lastEventEnd":"2026-10-04T14:47:28.442721891Z",
#     "threadIndex":{"threadObjects":29,"uniqueTids":29,"ambiguousTids":0,"missingTid":0}}

# 全窗口查询：81 条事件，4 个争用线程全部经 Thread.tid 匹配到堆对象
curl -s "http://localhost:7717/api/analyses/<analysisId>/recordings/<recordingId>/contention\
  ?from=2026-10-04T14:47:27.703730596Z&to=2026-10-04T14:47:28.442721891Z&eventLimit=1"
# => threads: [
#      {"javaThreadId":26,"threadMatch":"matched","events":21,"waitNanos":575434553,
#       "heapObjectId":"0x70e8f9408","retainedBytes":236,
#       "rootPath":{"rootObjectId":"0x70e8f01f0","pathLength":1,
#                   "edges":[{"fromClass":"java.lang.Thread[]","via":"[1]",...}]},
#       "locks":[{"monitorClass":"java.lang.Object","events":21,"waitNanos":575434553}]}, ...]

# 裁剪窗口（左闭右开）：相交 30 条，等待时长按裁剪后区间并集计算
curl -s ".../contention?from=2026-10-04T14:47:27.75Z&to=2026-10-04T14:47:28.00Z&eventLimit=0"
# => intersectingEvents: 30；每线程 events 7-8、waitNanos 为裁剪后并集

# 删除分析：录制、源转储与 .nbcache 一并清理，随后一切请求 404
curl -s -X DELETE http://localhost:7717/api/analyses/<analysisId>
```

## 代码结构

- `heapx.parse.HprofParser` — NetBeans 读取器 → 对象/浅堆/边/根，执行限制
- `heapx.model.HeapModel` — 不可变图（CSR 正/反邻接，id 索引）
- `heapx.graph.DominatorAnalysis` — 虚拟根 + 立即支配者 + 保留量 + 不可达标记
- `heapx.graph.PathFinder` — 反向 BFS 求到根的最短强引用路径
- `heapx.graph.CutPlanner` — 超源/超汇最小 s-t 割（Dinic）求最低代价断引用方案，
  含不可断证据路径与可达集合差计算
- `heapx.mask.HprofArrays` — 按 HPROF 记录结构定位 byte[]/char[] 载荷偏移
  （4/8 字节 ID、分段堆），绝不全文件搜字节
- `heapx.mask.SensitiveScanner` / `ScanResult` — 规则校验（不回显原文）与
  ASCII 精确匹配，产出绑定不可变 scanId 的命中清单（含根路径）
- `heapx.mask.MaskExporter` — 命中区间并集置零生成真实 HPROF 副本，
  统计去重数组数与实际改写
- `heapx.jfr.JfrParser` — `jdk.jfr.consumer` 读取真实 JFR，仅保留
  `jdk.JavaMonitorEnter`（起止、Java 线程 ID、锁类、栈），其余事件忽略；
  损坏文件 400，事件超限 422
- `heapx.jfr.ContentionRecording` / `ContentionEvent` — 绑定 analysisId 的
  不可变录制与事件模型
- `heapx.jfr.WindowAggregator` — 左闭右开窗口相交、先裁剪再合并的区间并集
  聚合（按线程与锁类）
- `heapx.model.ThreadIndex` — HPROF `java.lang.Thread`（含子类）声明的
  long `tid` 索引：唯一匹配 / 歧义 / 缺失
- `heapx.service.AnalysisService` — 分析注册表、限制、并发隔离、删除
  （保留源转储供扫描/导出，删除时清理源文件、扫描结果、JFR 录制与
  `.nbcache` 缓存目录）
- `heapx.http.Api` / `heapx.Main` — Javalin 路由与启动

## 测试

```bash
mvn -B test
```

- `DominatorAnalysisTest`：合成图验证支配语义（链上共享节点、环、多根共享、
  不可达标记、最短路径用真实边而非支配树边）。
- `CutPlannerTest`：合成图验证最小割语义（共享前缀只断一次、非最短路径上的
  边、环与同对象平行字段、多根、不可断证据、根作为目标、已不可达目标免费、
  新增不可达集合为可达集差）。
- `SensitiveScanTest`：敏感值扫描与遮除——重叠/多规则/不可达数组命中、
  不跨数组、char[] 元素匹配、响应不回显原文、规则校验拒绝、遮除字节计数
  （并集）、副本重新上传后排名一致且复扫为零、删除后 404；同一用例在
  8 字节 ID 与分段堆（HEAP_DUMP_SEGMENT）下重复验证。
- `EndToEndTest`：用自写 HPROF 生成器（4 字节 ID）产出真实转储文件，
  覆盖静态根、JNI 根、继承字段、对象/原始数组、`Reference.referent` 排除、
  不可达对象，并走完整 HTTP 上传 → 排行 → 根路径 → 断引用规划 → 删除流程；
  另验证对象/边超限、损坏文件与非法规划请求（重复候选、非法代价、
  不存在的引用）的拒绝。
- `JfrContentionTest`：测试 JVM 内用 `jdk.jfr.Recording` 录制**真实**锁争用
  （3 个线程争同一监视器），配合含 `java.lang.Thread#tid` 的合成 HPROF
  （唯一匹配、重复 tid 歧义、无事件 tid、子类 Thread、缺 tid 字段）走完整
  HTTP 流程：上传 → 录制概要 → 全窗口/裁剪窗口查询 → 删除 404；另含
  窗口聚合单元测试（先裁剪再合并、重叠不重复计数、左闭右开边界）、
  损坏 JFR 与 JSON 替代上传的 400 拒绝、删除时 `.nbcache` 残留清理。
