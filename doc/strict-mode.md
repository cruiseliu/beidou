<!--
本文档描述 strict 收包窗口机制（迁移 canary）的设计与现行实现：跨域触达的断言规则、
上下文传播、执行域盖章、显式截断点登记。机制仍在迁移期演进，本文档随裁定同步更新。
-->

# strict-mode —— strict 收包窗口与跨域触达 canary

## 0. 目的与总原则

- **定位**：strict 是迁移期 canary，不是安全沙箱。它回答一个问题："这段执行是否越过了
  它所在域的边界，触达了别的 actor 的状态？"
- **产出**：命中的调用链 = 漏改道的跨域点 = 迁移清单。AssertionError 经 strand/shim
  异常隔离记日志，**不中断服务**；窗口在 finally 中收口，正常/异常统一。
- **禁令（2026-10 裁定）**：不允许"传播了上下文又在任务入口清除"的免检形态。开启
  strict 即要求检查；债未消的调用点应让哨咬出来（显式豁免必须注释登记），而不是致盲。

## 1. 两个正交维度

- **`StrictWindow`（种类，infra）**：同一次收包处理可并存多种。
  - `STRAND`——本体触达哨：管线内经 `CharacterRef` / `MapleMapRef` 直调本体即断言。
  - `PACKET`——legacy Client 导航哨：管线内 getClient / legacy 发包按全局级别
    （`PACKET_STRICT_CLIENT`，现值 FAIL）响亮失败。
- **`PipelineContext`（因果上下文，infra）**：期望 owner（type + id）+ 在窗种类集合 + 模式。
  回答"这次执行属于谁的管线、此刻跑在谁的域上"。
- **`StrictWindow.Mode`（模式）**：窗口级字段（窗口内全部种类共用，随因果上下文传播、
  domain 盖章保留）。
  - `ASSERT`（默认）——违规抛 AssertionError，经 strand/shim 异常隔离记日志；
  - `LOG`——违规记 **error** 日志后放行，不中断执行。用途：新 opcode 纳入观察期
    （`openStrictWindow(w, Mode.LOG)`）、债显形但暂不阻断。

## 2. 状态的存放点

| 存放点 | 视角 | 消费方 |
|---|---|---|
| `PipelineContext`（ThreadLocal） | 执行期因果视图：任务体执行期间绑定，守卫静态读 | 三组守卫 |
| `Character.strictThread` + `strictKinds`（EnumSet） | 跨线程存续视角：窗口是否开着 | 预留（controller 选举过滤等；**当前无实际消费方，未接线**） |

kinds 以换引用发布（copyOf 后整体赋回），跨线程读恒见一致快照；收窗到空时解除线程绑定。
`PlayerStrand.domain()` 按其绑定角色解析执行域；角色未绑定（charlist 阶段）返回 null。

两个存放点都只是**窗口在执行期/存续期的投影**，不是持久状态——窗口本身的生命周期
见下节（opcode handler 粒度，随 dispatch 生灭）。

## 3. 开窗点：opcode handler 粒度的状态

**粒度原则**：strict window 是 **opcode handler 粒度**的状态。每个已迁移 opcode 的
InRouter case 为自己的 dispatch 开一窗，dispatch 结束（finally，幂等）即收口：

- **不持久**：不是会话/连接状态。窗口只在"该 opcode 的本次收包处理"存续，窗口外
  的一切执行（其余任务、定时器、脚本、空闲期）恒无窗。
- **不跨 opcode**：一次收包只属于一个 handler 的窗口；不同 case 的窗口互不重叠、
  不共享。
- **增量迁移的机制基础**：case 逐个纳入开窗——未列出的 opcode = 未纳入 strict 的
  迁移欠账，无窗是合法状态而非违规。
- **窗口收口 ≠ 上下文终结**：开窗期间 post 出去的任务携因果上下文继续传播（§4），
  域盖章随执行域换；后续任务里哨照咬。即"粒度"约束的是**开窗行为**，不是检查的
  作用范围。

另有两个非 in-route 开窗位，粒度同源（各自 handler/分支的 dispatch 期）：PLAYER_LOGGEDIN
大窗（登录 handler，全段 STRAND）、CharacterInventory.packetStrict（分支级 PACKET）。

| 窗口 | 种类 |
|---|---|
| in-route：MOVE_PLAYER、PLAYER_MAP_TRANSFER、NPC_TALK_MORE、QUEST_ACTION、CLOSE_RANGE_ATTACK | STRAND + PACKET |
| in-route：CHANGE_MAP、CHANGE_MAP_SPECIAL（导航未迁，第三参 false） | 仅 STRAND |
| PLAYER_LOGGEDIN 大窗（enterWorld 全段） | 仅 STRAND |
| CharacterInventory.packetStrict（特判分支债标记） | 仅 PACKET |
| 其余已迁 opcode（MOVE_LIFE、USE_ITEM、SPAWN_PET、NPC_ACTION 等） | 不开窗（待纳入） |

## 4. 传播与执行域盖章

- **捕获**：`Strand` / `ActorShim` 的 post/run/supply 入队时经 `PipelineContext.current()`
  捕获随 Task 冻结；null 流量零包装零开销。
- **建立**：executor 执行任务体前建立执行期视图（ThreadLocal），body 结束清除。
- **domain 盖章**：establish 时 owner 覆盖为**执行域**——`PlayerStrand` =
  `(CHARACTER, 绑定角色)`；`ActorShim` = 其宿主地图 `(MAP, mapId)`；基类裸 strand 不盖
  （保持原样）。意义：断言比对的是"触达对象是否属于**此刻执行域**"，跨 actor 后 owner
  随之换域；回投自己 actor 的续段（如可见性回程 apply）自动变回自访，无需特判。
- **guard 判定与 Task 对象解耦**：守卫静态读 ThreadLocal，不持有 Task；机制与
  `Strand.CURRENT` / `ActorShim.CURRENT` 同构。

## 5. 守卫（三组规则）

统一形状：`ctx` 非空且含对应种类时，断言"触达对象 == ctx.owner"；否则放行。违规确认后
按 ctx.mode 分流：`ASSERT` 抛出（经异常隔离记日志），`LOG` 记 error 后放行。

1. **`CharacterRef.notInStrictPipeline`**（挂在本组全部 ref 方法上，unref/getClient/
   sendPacket/getMap/getParty/…）：触达的 ref 必须 == ctx owner。
   - 角色 strand 上触自己 → 自访，放行（回程 apply 等合法续段）；
   - map actor 上触**任何** CHARACTER ref → owner 是 MAP，类型不匹配 → 咬；
   - 角色 strand 上触**他人** ref → id 不匹配 → 咬。
2. **`MapleMapRef.assertNotInStrictPipeline`**（unref 与直调组；statics() 与 shim 通道
   豁免）：触达的 map 必须 == ctx owner（MAP, mapId）。
   - 角色 strand 窗口内触任何 map → 咬（ owner 是 CHARACTER）；
   - map actor 触自己域的 map → 放行（shim 任务体直调本体字段是纪律而非哨）。
3. **`Player.assertNoLegacyClientNavigation`**：ctx 含 PACKET 且 owner ==
   (CHARACTER, chr) 时，legacy Client 导航按级别失败。**注**：现行为 owner 比对版——
   map actor 上的 client 导航（owner 是 MAP）不咬；"改为只看种类"曾是提议，未落地，
   待裁定。

## 6. 显式截断点（债登记；新增必须注释登记，撤债撤截断）

| 截断点 | 性质 | 消债条件 |
|---|---|---|
| `postLegacyPacket` | **永久语义**：过渡桥 = 上下文截断点（桥内 legacy 直发合法） | 不撤（S→C 语义化后随用户自然减少） |
| ~~`Battle.applyCloseRangeAttack` 入口~~ | ~~临时豁免~~ | **已撤除**（Monster Character 参数 ref 化完成，伤害链咬点以 LOG 模式显形为 phase 3 台账） |
| `MapleMapRef.onTransitionMobView` / `sendObjectPlacement` / `Character.releaseControlledMonsters` | 教义豁免：controller 选举/换届载荷属合法域上下文 | controller 体系语义化时重审 |

## 7. 辅助哨：strand 归属断言

`Strand.checkOnStrand`（**strict 已开：违反即抛**，异常经任务体隔离记日志）：哨位撒在
已迁移组件入口——CharacterPets 集合读入口、KeyedTimers.scheduleOrRun——暴露漏改道的
off-strand 访问。ActorShim.checkOnShim 仍为 warn（未翻转）。

## 8. 演化（为什么是现在这个形状）

1. **线程精确版**：窗口绑定开窗线程，跨 actor 任务结构性漏检（map 线程恒放行）。
2. **EnumSet 版**：种类可并存；执行侧仍是线程绑定，跨 actor 缺口不变。
3. **因果传播 + domain 盖章（现行）**：状态随任务走，owner 随执行域换——"免检"禁令
   由此成立：唯一合法的 `PipelineContext.clear()` 是登记在案的截断点。
4. **可见集值化（MapView）**：`Character.visibleMapObjects` 活引用集合（map actor 线程
   直写 `Set<MapObject>` + 活引用列表双向回投）是一条**无守卫、哨探不到**的跨域通道
   （不在本 doc 任何账本里）。已值化为 `MapView`（oid → 类型+模板 id，CHM 承接原并发
   语义），map 域写点全部改经 `MapObjectsViewMessage` 回投（MessageDispatcher 做
   mapId 接收方校验），ref 桥只剩 `isMapObjectVisible(oid)` 值读。教程 fire 21 → 20
   （撤掉的正是 `addVisibleMapObject` 直写那条）。oid 单调分配回绕前不复用，stale
   条目自愈；消费方：战斗 phase 1 的攻击目标 oid → mobId 解析（掉落链重构前置）。

## 9. 未决点

- Monster Character 参数 ref 化进行中（评估见会话记录；B 类 7 API + C 类深对象裁定），
  完成后撤 `Battle` 截断。
- packet 哨是否维持 owner 比对（现行为）或改为只看种类：未裁定。
- `Character` 存续视角字段的消费方未接线；若长期无消费，考虑收敛。
- CharacterPets / KeyedTimers 哨位自 strict 翻转后教程零命中，覆盖面待更广场景验证。
