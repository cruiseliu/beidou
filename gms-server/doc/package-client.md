# package-client —— remote 包设计原则

`org.gms.remote`（语义层）与 `org.gms.remote.v83`（v83 版本实现）负责管理和客户端的通信。
本文件是设计原则的权威描述；包内注释只保留局部事实，原则一律以本文件为准。

## 0. 职责与现状

- 管理游戏服务端与客户端之间的全部通信。
- 目前只覆盖 **S→C 单向**（C→S 预留，命名与包结构已为之留位）。
- 无连接/已断线时使用 `DisconnectedClient` 与 `RemoteUpdate.NOOP` 的静默实现——对齐
  `Character.sendPacket` 对 `client == null` 的容忍，断线角色的语义书写安全无害。

## 1. 语义层 / 版本实现两层

- **语义层**（`org.gms.remote`）面向 gameplay 逻辑，按领域分语义模块
  （`XxxModule`：stats / skills / basic / cooldown / inventory / pet）。
  **不允许暴露任何版本特定行为**——wire 形态、opcode、掩码位、魔法数字、编码基元、
  显示值都是实现私事；版本 hack 有唯一居所（版本实现内），换客户端版本 = 换一个实现。
- remote client 是 `PacketCreator` 的替代：本包**禁止引用** `PacketCreator`。
- **命名用游戏语义，不用协议动词**：`unlockActions()`（不是 enableActions）、
  `petFoodResponse(...)`（不是某 opcode + 魔法位）。
- 语义载荷（`XxxUpdate` / `SlotChange` / `SemanticEvent`）**自包含**：携带编码所需的
  全部事实，尽量避免实现层回读 `Character`。
- **冻结纪律（peek）**：携带可变实体引用的事件在**入域时**由版本实现 peek，冻结为
  版本自有的冻结事件——快照在入域时点抽取，翻译只读快照（翻译时机可能晚于构造，
  活引用会读到未来状态）。冻结所需的数据解析属于版本知识（例：宠物物品的数据在
  语义层对 pet 盲，由版本实现在 freeze 时解析补齐）。
- **无需快照的事件**：不经过冻结，原样通过，翻译按语义事件正常消费。
- **事件基类**：`ScopeRecord` 为事务段（`ScopeLog`）可存储记录的最高类型；
  `SemanticEvent`（`org.gms.remote.out.events`）是其封闭子接口（S→C 语义事件词表，
  版本不得伪造语义事件）；版本派生的冻结事件单独实现 `ScopeRecord`。事件 record 与
  接口同包，类型名带 Event 后缀。
- **收包（C→S，迁移中）**：管线 `shim（queued，投会话 strand）→ per-module pipeline
  （decode 产出 GMS083 事件 → translate 拓宽为版本无关事件）→ Handler 裸参数直调 →
  unlock（按事件类型由 pipeline 负责）`。dispatch 分模块（无中央 switch）；gameplay 不见
  ClientEvent/unlock。no-peek：v83 各层只读包与语义接口，角色状态读取全部在 gameplay
  Handler 实现内（例：PET_FOOD 包内无目标宠物，选宠是 gameplay 的事）。
  GMS083 事件 record 在 `gms083/client/packets`（byte/short 版本词汇）；语义事件 record 在
  `modules/<域>/client`（int 词汇，`ClientEvent` 封闭基接口）。
  **Handler 槽位表 `ClientEventHandlerRegistry` 挂 Player**（actor 的收包插座，会话级寿命）：
  构造期不自注册（构造上下文无 actor 可达：autosave/charlist 装载）——角色入场绑定时经
  `Character.bindClientHandlers` 聚合接线（register 调用在各组件内，角色内部组成不外泄给
  handler），on strand 执行；pipeline 经 `Player.current()` 环境取用（doc/12 权责语义）。

## 2. 事务（合并域）

- `RemoteClient.update()` 开启合并域（try-with-resources）：域内语义调用只记录
  （`SemanticEvent` 原始发生序，存入 `ScopeLog` 段），最外层 close = commit，
  按固定域序翻译并冲刷；嵌套开启为空收口（子段 adopt 并入父段）。
- **原则上所有未 commit 的调用均可回滚**：`drop()` O(1) 弃段，无一字节需要理解。
- 推论（硬规则）：**入口只记录不发送**。一切 wire 后果——包括地图广播的演出帧——
  都必须发生在 commit 边界之后，否则事务半发送、drop 撤不回。
- **合并边界 = 游戏语义单元**：调用方按"一件完整的事"开一个事务，处理完一次 commit；
  事务不跨线程使用，不跨地图迁移（广播受众在入口解析）；commit/close 幂等。
- 同一事务内同字段重复提交：**后写覆盖**（值是绝对值，语义安全）。

## 3. 语义模块与 translator 的多对多映射

语义模块（XxxModule）与版本实现的翻译器（XxxTranslator）**不一一对应**：

- `PetModule` 的面板事件落在 inventory 域的物品体刷新（v83 无专用面板包）；
- `Sp` / `Basic` / `UnlockActions` 骑 stats 域的 STAT_CHANGED 包型；
- 一个语义事件可同时落多个 translator（宠物面板 → inventory body 变更 + pet 域演出帧）。

wire 上怎么合并、拆分、搭载是实现私事。地图决定"发给谁"，包的构建归 remote。

## 4. 版本实现包结构

版本实现放 `org.gms.remote.<VERSION>`（现为 `v83`）。direction（out/in）紧跟版本根，
kind 在后（`<scope>.<direction>.<kind>`）；模块调用面拆分在各域 route，门面承载版本协作机器：

```
org.gms.remote.v83
├── V83RemoteClient        // route 门面：事务作用域/入口冻结/多对多映射(deliver)/冲刷/传输
└── out/
    ├── route/XxxRoute           // 模块调用面（每语义模块一个，事件 sink 构造注入）
    ├── translate/XxxTranslator
    ├── packet/XxxPacket
    └── FrozenInventoryEvent     // 版本派生的冻结事件
```

## 5. route 层

`V83RemoteClient` 是两层模块之间的 wire：实现全部语义模块接口，把语义调用转成
`SemanticEvent` 并 dispatch（开域入段 / 无域即时翻译冲刷），commit 时按固定域序
（stats → skills → cooldown → inventory）发送各 translator 的帧。

- **模块调用面已按模块拆分**（`out/route/XxxRoute`，每语义模块一个）：route 只把
  模块调用构造成事件并交付 sink；事件 sink 与即时 wire 适配由门面构造注入，
  route 不感知作用域状态。
- 对参数**不做理解、只透传**：语义调用与合并域书写共用同一批 route 单例，
  事件去向由作用域状态决定，句柄（`Handle`）不参与路由。
- 多对多映射（deliver）与冲刷序留驻门面，是唯一维护点。

## 6. translate 层

`XxxTranslator`（`out/translate`）把 **gameplay 使用的数据表示**翻译成 **packet 携带的数据表示**：
语义查表与规范化（cash 序列号三选一、成长经验 nibble、到期 wire 映射
EXPIRED/PERMANENT/日期、tameness min(30000) 截断、名字 -> 会话编码字节）。

**不做二进制编解码**：translate 层产出 packet record（树），不操作字节流。

## 7. packet 层

- **每种 opcode 一个树状 record**（`out/packet`），实现 `V83Packet` 接口（`opcode()` 返回 `SendOpcode`
  枚举常量，实例 `encode()` 编出整帧）；一个 opcode 内的多种形态
  用嵌套 sealed body 表达（如 `InventoryOperationPacket.ItemBody`、
  `StatChangedPacket.Body.Stats/PetIds`）。
- opcode 之间可以有共用子树（如宠物/装备/消耗品共用的物品体头部）。
- packet 层零字符集知识（字符串以会话编码字节传入）、零语义查表（只做字段排布），
  wire 常量（掩码位、历史宽度表）内聚在 record 内。
- **发送侧可观测性归 route 层**：`V83RemoteClient.send`/`wire` 是全部 wire 后果
  （含地图广播帧）的统一出口，在此 encode 并记日志——debug 级 packet record JSON、
  trace 级整帧 hex bytes（logger `org.gms.remote`，由日志级别门控，
  不走 `use_debug_show_packet` 开关）；translate 层与 packet record 不自行编码、不记日志。
