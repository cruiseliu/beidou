<!--
本文档是面向所有开发者的模块设计文档，包含 org.gms.remote 模块的使用指南和编辑该模块时必须遵守的原则。
本文档不是 agent 工作记录，禁止写入其他开发者不会关心的多余细节，比如某个 event 或 packet 的具体实现。
-->

# package-client —— remote 包设计原则

`org.gms.remote`（语义层）与 `org.gms.remote.gms083`（gms083 版本实现）负责管理和客户端的通信。
本文件是设计原则的权威描述；包内注释只保留局部事实，原则一律以本文件为准。

## 0. 职责与现状

- 管理游戏服务端与客户端之间的全部通信。
- 目前只覆盖 **S→C 单向**（C→S 预留，命名与包结构已为之留位）。
- 无连接/已断线时使用 `DummyClient` 的静默实现（自指实现全部模块面，调用静默）——对齐
  `Character.sendPacket` 对 `client == null` 的容忍，断线角色的语义书写安全无害。

## 1. 语义层 / 版本实现两层

- **语义层**（`org.gms.remote`）面向 gameplay 逻辑，按领域分语义模块
  （`XxxModule`：stats / skills / basic / inventory / pet；C→S 方向另有
  map / npc）。
  **不允许暴露任何版本特定行为**——wire 形态、opcode、掩码位、魔法数字、编码基元、
  显示值都是实现私事；版本 hack 有唯一居所（版本实现内），换客户端版本 = 换一个实现。
- remote client 是 `PacketCreator` 的替代：本包**禁止引用** `PacketCreator`。
- **命名用游戏语义，不用协议动词**：`unlockActions()`（不是 enableActions）、
  `petFoodResponse(...)`（不是某 opcode + 魔法位）。
- **模块方法不暴露版本类型，也不暴露事件类型**：gameplay 传语义实体
  （Character/Pet/Item）或基础类型；事件（`XxxEvent`）由版本 route 在入口包入
  （如 `BasicModule.initialize(Character)` → route 内构造 `InitializeEvent`）。
- **语义载荷（`XxxUpdate` / `SlotChange` / 事件 record）自包含**：携带编码所需的
  全部事实，尽量避免实现层回读 `Character`。
- **冻结纪律（peek）**：携带可变实体引用的事件在**入域时**由版本实现 peek，冻结为
  版本自有的冻结事件——快照在入域时点抽取，翻译只读快照（翻译时机可能晚于构造，
  活引用会读到未来状态）。冻结所需的数据解析属于版本知识（例：宠物物品的数据在
  语义层对 pet 盲，由版本实现在 freeze 时解析补齐）。
  现存欠账：`InitializeEvent` 携带 Character 活引用，wire 事实派生在 deliver 时点
  （BasicRouter onInitialize 处 FIXME——不完整 freeze，完整入域时快照以后再修）。
- **无需快照的事件**：不经过冻结，原样通过，翻译按语义事件正常消费。
- **事件基类**：`ServerEventBase` 为事务段（`EventLog`）可存储记录的最高类型；
  `ServerEvent` 是其 S→C 封闭子接口（语义事件词表，版本不得伪造语义事件）；版本派生的
  冻结事件实现 `ServerEventBase`。事件 record 与模块接口同模块树
  （`modules/<域>/server`），类型名带 Event 后缀。
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

- `RemoteClient.batch()` 开启合并域（try-with-resources，返回 `RemoteClientBatch`）：
  域内语义调用只记录（`ServerEvent` 原始发生序，存入 `EventLog` 段），最外层 close =
  commit，按固定域序翻译并冲刷；嵌套开启为空收口（子段 adopt 并入父段）。
- **原则上所有未 commit 的调用均可回滚**：`drop()` O(1) 弃段，无一字节需要理解。
- **推论（硬规则）：域内入口只记录不发送**。一切 wire 后果——包括地图广播的演出帧——
  都必须发生在 commit 边界之后，否则事务半发送、drop 撤不回。
- **合并边界 = 游戏语义单元**：调用方按"一件完整的事"开一个事务，处理完一次 commit；
  事务不跨线程使用，不跨地图迁移（广播受众在入口解析）；commit/close 幂等。
- 同一事务内同字段重复提交：**后写覆盖**（值是绝对值，语义安全）。
- **进不进合并域是 caller 的事**：route 一律 `client.schedule(dest, event)`——无开域时
  机器即时 deliver + 冲刷，开域时入段 commit 回放。实现不得以"某调用时机敏感"为由
  自定直发旁路（原 SET_FIELD 直发裁定已废除，见 doc/12 §21 追记 10）。

## 3. 语义模块与 translator 的多对多映射

语义模块（XxxModule）与版本实现的翻译器（XxxTranslator）**不一一对应**：

- `PetModule` 的面板事件落在 inventory 域的物品体刷新（v83 无专用面板包）；
- `Sp` / `Basic` / `UnlockActions` 骑 stats 域的 STAT_CHANGED 包型；
- 一个语义事件可同时落多个 translator（宠物面板 → inventory body 变更 + pet 域演出帧）；
- `BasicModule.initialize`（进图客户端视图初始化）落 basic 域自有包：
  `SetFieldTranslator` 整包快照 → `SetFieldPacket`，另有 `KeymapTranslator` →
  `KeymapPacket`（键位表 + 自动用药绑定 91/92 槽派生）、`QuickslotTranslator` →
  `QuickslotPacket`、`MacrosTranslator` → `MacrosPacket`；`updateMacros`（运行期宏表
  重推）经 `MacrosEvent` 复用同一包。

wire 上怎么合并、拆分、搭载是实现私事。地图决定"发给谁"，包的构建归 remote。

## 4. 版本实现包结构

版本实现放 `org.gms.remote.<VERSION>`（现为 `gms083`）。模块调用面拆分在各域 router，
门面承载版本协作机器：

```
org.gms.remote.gms083
├── Gms083                     // 门面：schedule/冲刷序机器（基类状态机）+ wire 传输
│                              //   （send/toLegacyPacket）+ 模块访问器；不出现具体事件类型
├── Gms083Routers              // route 装配
├── Gms083Translators          // translator 装配（charset 构造注入）
├── ServerTranslator           // translator 契约：isEmpty / flush() -> List<V83Packet>
├── server/
│   ├── routers/XxxRouter      // 每语义模块一个（模块出脸 + freeze + deliver + flush）
│   ├── translators/XxxTranslator
│   ├── packets/XxxPacket
│   └── events/                // 版本派生的冻结事件（FrozenInventoryEvent）
├── client/                    // C→S（见 §1 收包）：packets / pipelines / translate
└── utils/ByteBufBuilder       // wire 组装器（write 系列同 OutPacket 面，charset 构造期固定）
```

## 5. route 层

route 是语义模块与 translator 之间的 wire：每个语义模块一个 `XxxRouter`
（`server/routers`，实现模块接口 + `ServerEventDest`，一类四职：模块出脸 + freeze +
deliver + flush）。

- route 只把模块调用构造成事件并 `client.schedule` 交付；事件去向由作用域状态决定
  （§2），route 不感知作用域状态。
- 对参数**不做理解、只透传**：语义调用与合并域书写共用同一批 route 单例，
  句柄（`Handle`）不参与路由。
- deliver 内的事件 → translator 分派（多对多下沉，如 PetPanel → inventoryT）在各 route；
  冲刷序（stats → skills（含冷却包，原 cooldown 域并入）→ inventory，业务序显式书写）在门面 `flushAll`。

## 6. translate 层

`XxxTranslator`（`server/translators`）把 **gameplay 使用的数据表示**翻译成 **packet 携带的数据表示**：
语义查表与规范化（cash 序列号三选一、成长经验 nibble、到期 wire 映射
EXPIRED/PERMANENT/日期、tameness min(30000) 截断、可充值 wire 数量 = charge）在
translator（或其调用的条目工厂）**构造期**完成并固化为字段；wire 值
（expiration/completionTime/timestamp 等）在此换算完毕，packet 字段不再做换算。

**不做二进制编解码**：translate 层产出 packet record（树），不操作字节流。

**依赖方向（硬规则）**：translators → packets 单向，packets 禁止 import translators；
外部类型（Character/BuddyList/model pojo）不得进入 packets——在 translator 展开为纯字段
record（如 `SetFieldPacket.NewYear.Entry`）。

## 7. packet 层

- **每种 opcode 一个树状 record**（`server/packets`），实现 `V83Packet` 接口（`opcode()` 返回 `SendOpcode`
  枚举常量，实例 `encode()` 编出整帧）；一个 opcode 内的多种形态
  用嵌套 sealed body 表达（如 `StatChangedPacket.Body.Stats/PetIds`）。
- opcode 之间可以有共用子树（inventory 条目体：INVENTORY_OPERATION 条目与 SET_FIELD
  inventory 段共用同一 record 树，查表在其工厂构造期完成）。
- packet 层零语义查表（encode = 纯字段重放，只做字段排布）；**字符集是 builder 的
  构造期属性**（`ByteBufBuilder` 构造时固定，默认 lang-0）——字符串以 String 传入、
  encode 时经 builder charset 编码，消费方以 lang-0 builder 输出即为既定字节。
- **发送侧可观测性归 route 层**：`Gms083.send`/`toLegacyPacket` 是全部 wire 后果
  （含地图广播帧）的统一出口，在此 encode 并记日志——debug 级 packet record JSON、
  trace 级整帧 hex bytes（logger `org.gms.remote`，由日志级别门控，
  不走 `use_debug_show_packet` 开关）；translate 层与 packet record 不自行编码、不记日志。
