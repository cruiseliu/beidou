# 脚本引擎（ESM / actorscripts）

ESM 新脚本系统的架构与约束。与 legacy js 系统（`scripts/`，AbstractScriptManager +
Invocable）并行并存、互不引用；新脚本一律进 `actorscripts/`。
上下文：actor 模型见 doc/13，语义层契约见 package-client.md。

## 1. 双系统并存

| | ESM 新系统 | legacy 旧系统 |
|---|---|---|
| 根目录 | `actorscripts/` | `scripts/`（+ `scripts-<语言>/` 语言目录） |
| 引擎形态 | GraalVM ES Module（`Source` + module MIME，`import`/`export`） | Invocable 缓存 + `invokeFunction` |
| 状态作用域 | per-client（Context per 角色，模块级状态不跨角色） | 全局共享 |
| 宿主 | `CharacterScriptRunner`（per 角色） | `AbstractScriptManager` 单例 |

分流按**文件存在性**裁定（`QuestScript.exists` 先例）：ESM 文件存在走新系统，否则落
legacy 路径。事件脚本（`scripts/event`）暂属 legacy（`Channel.getEvents`）。

## 2. 装载与宿主

- **`JsModule`**（`org.gms.scripting`）：模块句柄。根目录 `actorscripts/`；
  `normalizeKey` 规范化缓存键并做越界校验（路径不得逃出根目录）；
  `get(导出名)` 取命名导出、`call(函数, args)` 执行。
- **`CharacterScriptRunner`**（client/character）：per 角色宿主。polyglot Context
  归角色所有；模块注册表 `computeIfAbsent`（同角色同文件只 eval 一次；热重载预留
  清空接口）。**线程模型**：所有方法必须在 owning strand 上调用（经宿主 run/call
  串行进 Context，无锁）。
- **`player` 全局绑定**：宿主把 Player actor 注入 context bindings；`bind_player.js`
  的 `player` = Java 侧聚合 `org.gms.client.scripting.api.PlayerApis`——显式白名单
  分面 `{ basic, stats, inventory, message, talk }`，模块顶层 eager 构造（分面无状态，构造
  不触角色），方法内 character 经 actor context（`Player.require`）现取，不存槽；
  过渡期旧全通代理保留为 `player_old`（存量脚本迁移完成后删除）。
  **脚本装载只在 player 域发生**：Context/模块均惰性——首个脚本任务（钩子派发、
  portal 门、任务脚本）才创建；auth/charlist 视图装载走 `CharacterViewEntry`
  （纯 JDBC 快照，不构造 Character、不触脚本，[esm-audit] 打点实测）。模块顶层
  导出必须装载期可求值、不依赖角色；需要 Spring 的资源（WZ provider 等）用惰性
  初始化（bind_player.js 先例）。
- **JS 侧访问语义层**：`player_old.getRemote()`（public）。注意 `Character.remote()`
  是包私有，GraalJS 按修饰符拦截，JS 里调用报 not a function。

## 3. 目录布局

```
actorscripts/
├── lib/
│   ├── bind_player.js    # player actor 绑定层（唯一允许的 Java.type 面，见 §4 约束 1；
│   │                     #   其他 actor 将来另立 bind_<actor>.js）
│   └── interaction.js   # 对话链 InteractionManager（NEXT_PREV 哨兵 / i18n）
├── map/
│   ├── common/          # 地图脚本共享层（组合 util；模块相对引用 ./common/x.js）
│   │   └── tutorial.js  # 教学门工厂 advice(n)/mapAdvice() + showInstruction
│   └── <MAP_ID>.js      # 每图一模块：按 WZ portal script 名同名导出函数
├── quest/<id>.js         # ESM 任务（曾居 quest/esm/<id>.mjs，临时迁移态已归位）
├── item/<name>.js       # 道具钩子
└── server/ap_assigner/default.js  # AP 自动分配器（export default）
```

## 4. 约束（硬规则）

1. **`bind_player.js` 只放纯 ECMAScript 写不出来的东西**——Java.type 常量、WZ provider、
   宿主绑定代理、I18n、服务器时钟/定时器。组合式 util（如 `showInstruction`）归
   使用方共享目录（`map/common/` 等）。
2. **相对 import 按导入文件所在目录解析，不是 actorscripts 根**：`map/0.js` 引共享
   层写 `./common/x.js`；写 `../common/x.js` 会解析到 `actorscripts/common/`
   （文件不存在才炸，容易漏）。
3. **脚本执行归 player 域（player strand）**，不得进 map actor 任务体——脚本会经
   gameplay 回调 changeMap 再入 map actor，脚本入 actor 即环死锁。
4. **模块顶层装载期纯净**：不触碰需要 Spring 的资源与角色状态（provider 惰性取用，
   bind_player.js 先例）；跨模块共享数据用 `../../lib/` 相对 import。
5. **失败语义分层**：模块缺失 / 无同名导出 = **无脚本**，由调用方降级（如 portal
   门回 unlock）；脚本执行异常 = fail-safe 记日志、服务继续（宿主
   "脚本执行异常（宿主存活）"；portal 桥吞异常回 unlock，对齐旧
   PortalScriptManager 语义）。
6. **字符串表参数约定**：`getScriptStrings(file)` 的 file **不带 `.xml` 扩展名**
   （底层 `getData` 自动补，传全名等于找 `x.xml.xml`）；返回 JS Map，键**带 `$`
   包裹**（`$SCRIPTSTRING_TUTORIAL_0$`）；文件缺失抛错（对白文本缺失不是合法
   运行态）。语言维度由 provider 目录回退决定（`wz-zh-CN` 优先）。
7. **wire 禁令**：actorscripts 禁止引用 `PacketCreator`——wire 后果一律经语义模块
   （`player.getRemote().xxx()`）表达；跨包语义（如 ShowHint 自带 unlock）由版本
   route 拼装（多对多下沉），脚本无感。

## 5. 接入点（Java → 脚本）

| 接入点 | 模块路径 | 派发形态 | 失败语义 |
|---|---|---|---|
| 道具钩子 | `item/<name>.js` | `onEnterInventory` / `onLeaveInventory` / `onUse` | 文件缺失 → dead（warn 一次） |
| ESM 任务 | `quest/<id>.js` | InteractionManager entry 导出（`q<id>s`/`q<id>e`），async/await 瀑布对话（接入点 `client.scripting.QuestScript`，player strand） | 无文件 → legacy QuestScriptManager |
| portal 脚本 | `map/<MAP_ID>.js` | 按 WZ portal script 名同名导出；返回 true = 门已处理 | 无模块/无导出/异常 → unlock 兜底（GenericPortal 内围栏断言保留，防其他调用方） |
| AP 分配器 | `server/ap_assigner/default.js` | export default APAssigner | |
| map 脚本（onUserEnter/onFirstUserEnter） | 未接桥 | 围栏断言已按裁定关闭：遇脚本地图静默跳过（FIXME 桥接排期） | |

### portal 脚本桥示例

`CharacterMap.enterPortal` 在门校验/交易取消后拦截 script 门：
`runPortalScript(scriptName)` → `moduleFor("map/<mapid>.js")` → 同名导出 → `call`。
返回 true = 门已处理（演出/warp 由脚本语义决定）；false = 无脚本门，unlock 兜底。

## 6. 语义联动示例

`showInstruction(message, width, height)` → `message().showHint(...)` →
`ShowHintEvent` → `MessageRouter` 拼双包（PLAYER_HINT + unlock STAT_CHANGED）。
解锁随语义由版本 wire 拼装（多对多下沉，PetPanel 先例），脚本不感知也不负责。
