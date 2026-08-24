# Yet Another BeiDouMS Fork #

实验性的第三方 BeiDouMS fork，早期开发阶段。

* 短期目标：改善可维护性
* 中期目标：优化单机体验
* 长期目标：游戏内容按版本模组化

新法代码，古法 review。

## 可维护性重构 ##

BeiDouMS 主要开发痛点：

 1. 超长单文件
 2. 遍地分支特判
 3. 数据协议和游戏逻辑高度耦合
 4. JS 脚本框架粗糙

### 架构级重构计划 ###

 1. 巨型类拆分
     1. [x] ~~拆分 Character.java~~
     2. [ ] 拆分 Packet.java
     3. [ ] 拆分 MapleMap.java
 2. ID 特判数据化
     1. [ ] 聚合职业数据 (In Progress)
     2. [x] ~~聚合武器类型数据~~
     3. [ ] 聚合技能数据
 3. 剥离通信层
 4. 重构脚本框架
     1. [ ] 支持 ESM (In Progress)
     2. [ ] 支持 i18n
     3. [ ] 常用脚本逻辑 library 化
 5. 重构数据库
     1. [x] ~~迁移 SQLite~~
     2. [ ] 修正每次访问重新分配 ID 的神秘用法
     3. [ ] 非索引列动态化 (In Progress)
     4. [ ] 支持 MariaDB + SQLite 双引擎

### 模块重构进度 ###

 1. Character 部分
    1. [ ] 属性 (In Progress)
    2. [ ] 职业 (In Progress)
    3. [ ] 技能 (In Progress)
    4. [ ] Buff (In Progress)
