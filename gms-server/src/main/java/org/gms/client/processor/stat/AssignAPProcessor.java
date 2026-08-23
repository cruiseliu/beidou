/*
	This file is part of the OdinMS Maple Story Server
    Copyright (C) 2008 Patrick Huy <patrick.huy@frz.cc>
		       Matthias Butz <matze@odinms.de>
		       Jan Christian Meyer <vimes@odinms.de>

    Copyleft (L) 2016 - 2019 RonanLana (HeavenMS)

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as
    published by the Free Software Foundation version 3 as published by
    the Free Software Foundation. You may not use, modify or distribute
    this program under any other version of the GNU Affero General Public
    License.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/
package org.gms.client.processor.stat;

import org.gms.client.character.Character;
import org.gms.client.Client;
import org.gms.client.JobEnum;
import org.gms.client.Skill;
import org.gms.client.SkillFactory;
import org.gms.client.PacketStat;
import org.gms.client.autoban.AutobanFactory;
import org.gms.client.inventory.Equip;
import org.gms.client.inventory.InventoryType;
import org.gms.client.inventory.Item;
import org.gms.config.GameConfig;
import org.gms.constants.skills.BlazeWizard;
import org.gms.constants.skills.Brawler;
import org.gms.constants.skills.DawnWarrior;
import org.gms.constants.skills.Magician;
import org.gms.constants.skills.ThunderBreaker;
import org.gms.constants.skills.Warrior;
import org.gms.net.packet.InPacket;
import org.gms.scripting.JsModule;
import org.gms.util.PacketCreator;
import org.gms.util.Randomizer;
import org.graalvm.polyglot.Value;

import java.util.*;

/**
 * 处理角色属性点(AP)分配的核心处理器
 * 主要功能：
 * 1. AP自动分配 - 根据职业智能分配剩余AP
 * 2. AP手动分配 - 玩家手动选择分配属性
 * 3. AP重置 - 允许玩家重新分配属性点
 * 实现了不同职业的AP分配策略，包括：
 * - 战士(Warrior)、魔法师(Magician)、弓箭手(Bowman)
 * - 飞侠(Thief)、海盗(Pirate)等
 * 同时处理HP/MP的AP分配和重置逻辑
 */
public class AssignAPProcessor {

    // 配置参数全局变量，减少重复读取
    /** 使用HeavenMS的属性点自动分配器 */
    private static boolean useServerAutoAssigner;
    /** 自动分配属性点到主属性，除非主属性已经达到职业设定最大，才会分配到次属性 */
    private static boolean useAutoAssignSecondaryCap;
    /** 强制洗血蓝的点数只能加到血蓝，不能分配到其他属性 */
    private static boolean useEnforceHpmpSwap;
    /** 启用HeavenMS的血蓝更新机制，升级、分配属性、转职等按照比例更新血量和蓝量 */
    /** 获取HP和MP是否随机化，有职业系数加成 */
    private static boolean useRandomizeHpmpGain;
    /** 属性点最大值 */
    private static int maxAp;

    // 重新加载配置方法
    public static void reloadConfig() {
        useServerAutoAssigner = GameConfig.getServerBoolean("use_server_auto_assigner");
        useAutoAssignSecondaryCap = GameConfig.getServerBoolean("use_auto_assign_secondary_cap");
        useEnforceHpmpSwap = GameConfig.getServerBoolean("use_enforce_hpmp_swap");
        useRandomizeHpmpGain = GameConfig.getServerBoolean("use_randomize_hpmp_gain");
        maxAp = GameConfig.getServerInt("max_ap");
    }

    /**
     * 自动分配角色的剩余AP点
     * 处理流程：
     * 1. 检查是否有剩余AP可分配
     * 2. 锁定客户端防止并发操作
     * 3. 根据职业类型采用不同的分配策略：
     *    - 魔法师：主INT副LUK
     *    - 弓箭手：主DEX副STR
     *    - 飞侠：主LUK副DEX
     *    - 战士：主STR副DEX
     * 4. 考虑装备属性需求优化分配
     * 5. 应用分配结果并更新角色状态
     * 6. 解锁客户端
     *
     * @param inPacket 客户端输入数据包
     * @param c 客户端连接对象
     */
    public static void APAutoAssignAction(InPacket inPacket, Client c) {
        reloadConfig(); // 重新加载配置
        Character chr = c.getPlayer(); // 获取当前玩家角色对象
        if (chr.getRemainingAp() < 1) { // 检查玩家剩余AP是否小于1
            return; // 如果不足1点AP则直接返回
        }

        Collection<Item> equippedC = chr.getInventory(InventoryType.EQUIPPED).list(); // 获取玩家当前装备的物品列表

        c.lockClient(); // 锁定客户端，防止并发操作
        try {
            int[] statGain = new int[4]; // 创建属性增益数组，用于存储STR/DEX/LUK/INT的增益值
            int[] statUpdate = new int[4]; // 创建属性更新数组，用于存储更新后的属性值
            statGain[0] = 0; // 初始化力量(STR)增益为0
            statGain[1] = 0; // 初始化敏捷(DEX)增益为0
            statGain[2] = 0; // 初始化运气(LUK)增益为0
            statGain[3] = 0; // 初始化智力(INT)增益为0

            int remainingAp = chr.getRemainingAp(); // 获取玩家剩余的AP点数
            inPacket.skip(8); // 跳过数据包的前8个字节

            if (useServerAutoAssigner) { // 检查是否使用服务器自动分配器
                // --------- Ronan Lana's AUTOASSIGNER (JS 版) ---------
                // 分配逻辑已移植到 scripts/server/ap_assigner/default.js，
                // 通过 JsModule 以 ESM 方式加载调用；装备数据由 Java 侧收集后传入 ctx。
                byte opt = inPacket.readByte(); // 读取数据包中的选项字节(对海盗职业的自动分配有用)

                // 装备属性收集（与 JS 端 ctx 字段一一对应）
                int eqpStr = 0, eqpDex = 0, eqpLuk = 0, eqpInt = 0; // 初始化装备属性总和变量
                List<Short> eqpStrList = new ArrayList<>(); // 创建装备STR值列表
                List<Short> eqpDexList = new ArrayList<>(); // 创建装备DEX值列表
                List<Short> eqpLukList = new ArrayList<>(); // 创建装备LUK值列表

                Equip nEquip; // 声明装备对象变量

                // 遍历所有已装备的物品，收集属性信息
                for (Item item : equippedC) {
                    nEquip = (Equip) item; // 将物品转换为装备对象
                    if (nEquip.getStr() > 0) { // 检查装备是否有STR加成
                        eqpStrList.add(nEquip.getStr()); // 将STR值添加到列表中
                    }
                    eqpStr += nEquip.getStr(); // 累加STR总值

                    if (nEquip.getDex() > 0) { // 检查装备是否有DEX加成
                        eqpDexList.add(nEquip.getDex()); // 将DEX值添加到列表中
                    }
                    eqpDex += nEquip.getDex(); // 累加DEX总值

                    if (nEquip.getLuk() > 0) { // 检查装备是否有LUK加成
                        eqpLukList.add(nEquip.getLuk()); // 将LUK值添加到列表中
                    }
                    eqpLuk += nEquip.getLuk(); // 累加LUK总值

                    //if(nEquip.getInt() > 0) eqpIntList.add(nEquip.getInt()); //not needed...
                    eqpInt += nEquip.getInt(); // 累加INT总值
                }

                // 对装备属性列表进行降序排序
                eqpStrList.sort(Collections.reverseOrder()); // STR降序排序
                eqpDexList.sort(Collections.reverseOrder()); // DEX降序排序
                eqpLukList.sort(Collections.reverseOrder()); // LUK降序排序

                JobEnum stance = c.getPlayer().getJobStyle(opt); // 根据选项获取玩家的职业类型

                // 构造传给 JS 的上下文（字段与 default.js 的 ctx 一致）
                Map<String, Object> ctx = new HashMap<>();
                ctx.put("level", chr.getLevel());
                ctx.put("str", chr.getStr());
                ctx.put("dex", chr.getDex());
                ctx.put("int", chr.getInt());
                ctx.put("luk", chr.getLuk());
                ctx.put("ap", remainingAp);
                ctx.put("jobStyle", stance.name());
                ctx.put("eqpStrList", eqpStrList);
                ctx.put("eqpDexList", eqpDexList);
                ctx.put("eqpLukList", eqpLukList);
                ctx.put("eqpStr", eqpStr);
                ctx.put("eqpDex", eqpDex);
                ctx.put("eqpLuk", eqpLuk);
                ctx.put("useSecondaryCap", useAutoAssignSecondaryCap);
                ctx.put("maxAp", maxAp);

                // 调用 JS 分配器：export default APAssigner = { default: assign, beginner: assign }
                JsModule module = JsModule.importModule("server/ap_assigner/default.js");
                Value assigner = (Value) module.getDefault();
                Value result = assigner.getMember("default").execute(ctx);

                // 解析 JS 返回的分配结果 { str, dex, int, luk }
                statGain[0] = result.getMember("str").asInt(); // STR增益
                statGain[1] = result.getMember("dex").asInt(); // DEX增益
                statGain[3] = result.getMember("int").asInt(); // INT增益
                statGain[2] = result.getMember("luk").asInt(); // LUK增益

                // 更新玩家属性
                chr.assignStrDexIntLuk(statGain[0], statGain[1], statGain[3], statGain[2]);
                // 允许玩家行动
                c.sendPacket(PacketCreator.enableActions());

                // 发送分配结果通知给玩家
                c.sendPacket(PacketCreator.serverNotice(1,
                        "检测到更优AP分配方案：\r\n力量(STR): +" + statGain[0] +
                                "\r\n敏捷(DEX): +" + statGain[1] +
                                "\r\n智力(INT): +" + statGain[3] +
                                "\r\n运气(LUK): +" + statGain[2]));
            } else { // 不使用自动分配器的情况
                if (inPacket.available() < 16) { // 检查数据包是否完整
                    AutobanFactory.PACKET_EDIT.alert(chr, "Auto Assign数据包不完整"); // 记录异常
                    c.disconnect(true, false); // 断开客户端连接
                    return;
                }

                // 手动分配属性
                for (int i = 0; i < 2; i++) { // 循环处理两种属性
                    int type = inPacket.readInt(); // 读取属性类型
                    int tempVal = inPacket.readInt(); // 读取分配值
                    if (tempVal < 0 || tempVal > remainingAp) { // 验证分配值是否合法
                        return; // 不合法则返回
                    }
                    gainStatByType(PacketStat.getBy5ByteEncoding(type), statGain, tempVal, statUpdate); // 分配属性
                }

                // 更新玩家属性
                chr.assignStrDexIntLuk(statGain[0], statGain[1], statGain[3], statGain[2]);
                // 允许玩家行动
                c.sendPacket(PacketCreator.enableActions());
            }
        } finally {
            c.unlockClient(); // 确保最终解锁客户端
        }
    }

    /**
     * 按属性类型增加属性点
     * 处理流程：
     * 1. 检查增益值是否有效
     * 2. 根据属性类型(STR/DEX/INT/LUK)更新对应属性
     * 3. 确保不超过服务器配置的最大值
     * 4. 返回未能分配的剩余点数
     *
     * @param type 属性类型
     * @param statGain 各属性增益数组
     * @param gain 要增加的点数
     * @param statUpdate 当前属性值数组
     * @return 未能分配的剩余点数
     */
    private static int gainStatByType(PacketStat type, int[] statGain, int gain, int[] statUpdate) {
        reloadConfig();
        if (gain <= 0) {
            return 0;
        }

        int newVal = 0;
        switch (type) {
            case STR -> {
                newVal = statUpdate[0] + gain;
                if (newVal > maxAp) {
                    statGain[0] += (gain - (newVal - maxAp));
                    statUpdate[0] = maxAp;
                } else {
                    statGain[0] += gain;
                    statUpdate[0] = newVal;
                }
            }
            case INT -> {
                newVal = statUpdate[3] + gain;
                if (newVal > maxAp) {
                    statGain[3] += (gain - (newVal - maxAp));
                    statUpdate[3] = maxAp;
                } else {
                    statGain[3] += gain;
                    statUpdate[3] = newVal;
                }
            }
            case LUK -> {
                newVal = statUpdate[2] + gain;
                if (newVal > maxAp) {
                    statGain[2] += (gain - (newVal - maxAp));
                    statUpdate[2] = maxAp;
                } else {
                    statGain[2] += gain;
                    statUpdate[2] = newVal;
                }
            }
            case DEX -> {
                newVal = statUpdate[1] + gain;
                if (newVal > maxAp) {
                    statGain[1] += (gain - (newVal - maxAp));
                    statUpdate[1] = maxAp;
                } else {
                    statGain[1] += gain;
                    statUpdate[1] = newVal;
                }
            }
        }

        if (newVal > maxAp) {
            return newVal - maxAp;
        }
        return 0;
    }
    /**
     * 执行AP重置操作，将一种属性转换为另一种属性
     * 处理流程：
     * 1. 锁定客户端防止并发操作
     * 2. 验证源属性是否有足够点数可重置
     * 3. 根据不同类型执行减点操作：
     *    - STR/DEX/INT/LUK: 直接减少
     *    - HP/MP: 特殊处理，有最小限制
     * 4. 将减去的点数加到目标属性
     * 5. 处理HP/MP变化时的血量/魔法值更新
     * 6. 解锁客户端
     *
     * @param c 客户端连接对象
     * @param APFrom 源属性类型
     * @param APTo 目标属性类型
     * @return 操作是否成功
     */
    public static boolean APResetAction(Client c, int APFrom, int APTo) {
        reloadConfig();
        c.lockClient();
        try {
            Character player = c.getPlayer();

            switch (APFrom) {
                case 64 -> { // str
                    if (player.getStr() < 5) {
                        player.message("你的力量(STR)不足，无法进行重置。");
                        c.sendPacket(PacketCreator.enableActions());
                        return false;
                    }
                    if (!player.assignStr(-1)) {
                        player.message("AP重置操作执行失败。");
                        c.sendPacket(PacketCreator.enableActions());
                        return false;
                    }
                }
                case 128 -> { // dex
                    if (player.getDex() < 5) {
                        player.message("你的敏捷(DEX)不足，无法进行重置。");
                        c.sendPacket(PacketCreator.enableActions());
                        return false;
                    }
                    if (!player.assignDex(-1)) {
                        player.message("AP重置操作执行失败。");
                        c.sendPacket(PacketCreator.enableActions());
                        return false;
                    }
                }
                case 256 -> { // int
                    if (player.getInt() < 5) {
                        player.message("你的智力(INT)不足，无法进行重置。");
                        c.sendPacket(PacketCreator.enableActions());
                        return false;
                    }
                    if (!player.assignInt(-1)) {
                        player.message("AP重置操作执行失败。");
                        c.sendPacket(PacketCreator.enableActions());
                        return false;
                    }
                }
                case 512 -> { // luk
                    if (player.getLuk() < 5) {
                        player.message("你的运气(LUK)不足，无法进行重置。");
                        c.sendPacket(PacketCreator.enableActions());
                        return false;
                    }
                    if (!player.assignLuk(-1)) {
                        player.message("AP重置操作执行失败。");
                        c.sendPacket(PacketCreator.enableActions());
                        return false;
                    }
                }
                case 2048 -> { // HP
                    if (useEnforceHpmpSwap) {
                        if (APTo != 8192) {
                            player.message("你只能将HP能力点重置到MP。");
                            c.sendPacket(PacketCreator.enableActions());
                            return false;
                        }
                    }
                    if (player.getHpMpApUsed() < 1) {
                        player.message("你没有足够的HP、MP属性点用于AP重置。");
                        c.sendPacket(PacketCreator.enableActions());
                        return false;
                    }
                    int hp = player.getMaxHp();
                    int level_ = player.getLevel();
                    if (hp < level_ * 14 + 148) {
                        player.message("你的HP总量不足，无法进行重置。");
                        c.sendPacket(PacketCreator.enableActions());
                        return false;
                    }
                    int curHp = player.getHp();
                    int hplose = -takeHp(player.getJob());
                    player.assignHP(hplose, -1);
                    player.updateHp(Math.max(1, curHp + hplose));
                }
                case 8192 -> { // MP
                    if (useEnforceHpmpSwap) {
                        if (APTo != 2048) {
                            player.message("你只能将MP能力点重置到HP。");
                            c.sendPacket(PacketCreator.enableActions());
                            return false;
                        }
                    }
                    if (player.getHpMpApUsed() < 1) {
                        player.message("你没有足够的HP、MP属性点用于AP重置。");
                        c.sendPacket(PacketCreator.enableActions());
                        return false;
                    }
                    int mp = player.getMaxMp();
                    int level = player.getLevel();
                    JobEnum job = player.getJob();
                    boolean canWash = true;
					if (job.isA(JobEnum.SPEARMAN)) {
                        canWash = mp >= 4 * level + 156;
                    } else if (job.isA(JobEnum.FIGHTER) || job.isA(JobEnum.ARAN1)) {
                        canWash = mp >= 4 * level + 56;
                    } else if (job.isA(JobEnum.THIEF) && job.getId() % 100 > 0) {
                        canWash = mp >= level * 14 - 4;
                    } else {
                        canWash = mp >= level * 14 + 148;
                    }
                    if (!canWash) {
                        player.message("你的MP总量不足，无法进行重置。");
                        c.sendPacket(PacketCreator.enableActions());
                        return false;
                    }
                    int curMp = player.getMp();
                    int mplose = -takeMp(job);
                    player.assignMP(mplose, -1);
                    player.updateMp(Math.max(0, curMp + mplose));
                }
                default -> {
                    c.sendPacket(PacketCreator.updatePlayerStats(PacketCreator.EMPTY_STATUPDATE, true, player));
                    return false;
                }
            }

            addStat(player, APTo, true);
            return true;
        } finally {
            c.unlockClient();
        }
    }
    /**
     * 手动分配AP点到指定属性
     * 处理流程：
     * 1. 锁定客户端防止并发操作
     * 2. 根据属性类型调用对应的分配方法
     * 3. 处理HP/MP分配时的特殊计算
     * 4. 解锁客户端
     *
     * @param c 客户端连接对象
     * @param num 目标属性类型编码
     */
    public static void APAssignAction(Client c, int num) {
        c.lockClient();
        try {
            addStat(c.getPlayer(), num, false);
        } finally {
            c.unlockClient();
        }
    }

    private static boolean addStat(Character chr, int apTo, boolean usedAPReset) {
        int maxHp, maxMp;
        switch (apTo) {
            case 64 -> { // 力量(STR)
                if (!chr.assignStr(1)) {
                    chr.message("无法重新分配AP");
                    chr.sendPacket(PacketCreator.enableActions());
                    return false;
                }
            }
            case 128 -> { // 敏捷(DEX)
                if (!chr.assignDex(1)) {
                    chr.message("无法重新分配AP");
                    chr.sendPacket(PacketCreator.enableActions());
                    return false;
                }
            }
            case 256 -> { // 智力(INT)
                if (!chr.assignInt(1)) {
                    chr.message("无法重新分配AP");
                    chr.sendPacket(PacketCreator.enableActions());
                    return false;
                }
            }
            case 512 -> { // 运气(LUK)
                if (!chr.assignLuk(1)) {
                    chr.message("无法重新分配AP");
                    chr.sendPacket(PacketCreator.enableActions());
                    return false;
                }
            }
            case 2048 -> { // HP
                maxHp = calcHpChange(chr, usedAPReset);
                if (!chr.assignHP(maxHp, 1)) {
                    chr.message("无法重新分配AP");
                    chr.sendPacket(PacketCreator.enableActions());
                    return false;
                } else if (usedAPReset) {
                    chr.dropMessage(6, "[重置卷轴] 最大HP +" + maxHp + " ↑");
                }
            }
            case 8192 -> { // MP
                maxMp = calcMpChange(chr, usedAPReset);
                if (!chr.assignMP(maxMp, 1)) {
                    chr.message("无法重新分配AP");
                    chr.sendPacket(PacketCreator.enableActions());
                    return false;
                } else if (usedAPReset) {
                    chr.dropMessage(6, "[重置卷轴] 最大MP +" + maxMp + " ↑");
                }
            }
            default -> {
                chr.sendPacket(PacketCreator.updatePlayerStats(PacketCreator.EMPTY_STATUPDATE, true, chr));
                return false;
            }
        }
        return true;
    }
    /**
     * 计算HP增加量（根据职业不同有不同成长）
     * 处理流程：
     * 1. 初始化默认HP增长参数
     * 2. 根据职业类型设置特定参数
     * 3. 处理战士/海盗职业的技能加成
     * 4. 根据配置计算最终HP增加值
     *
     * @param player 角色对象
     * @param usedAPReset 是否来自AP重置操作
     * @return HP增加量
     */
    private static int calcHpChange(Character player, boolean usedAPReset) {
        reloadConfig();  // 重新加载最新配置参数

        //========== 基础参数初始化 ==========//
        JobEnum job = player.getJob();  // 获取当前职业
        int MaxHP = 0;              // 最终HP增加值

        //========== 默认配置（适用于新手等未定义职业） ==========//
        int baseValue = 10;      // 基础增加值
        int resetValue = 8;      // AP重置时增加值
        int randomMin = 8;       // 随机最小值
        int randomMax = 12;      // 随机最大值
        Integer skillId = null;  // 需要检查的技能ID

        //========== 职业专属配置 ==========//
        // 战士/黎明战士（物理系职业）
        if (job.isA(JobEnum.WARRIOR) || job.isA(JobEnum.DAWNWARRIOR1)) {
            baseValue = 20;       // 标准模式下基础值
            resetValue = 20;      // 重置时增加值
            randomMin = 18;       // 随机范围18-22
            randomMax = 22;
            skillId = job.isA(JobEnum.DAWNWARRIOR1) ?
                    DawnWarrior.MAX_HP_INCREASE :
                    Warrior.IMPROVED_MAXHP;
        }
        // 战神（特殊战士职业）
        else if (job.isA(JobEnum.ARAN1)) {
            baseValue = 28;       // 固定模式增加值
            resetValue = 20;      // 重置时增加值
            randomMin = 26;       // 随机范围26-30
            randomMax = 30;
        }
        // 魔法师/烈焰巫师（法系职业）
        else if (job.isA(JobEnum.MAGICIAN) || job.isA(JobEnum.BLAZEWIZARD1)) {
            baseValue = 6;
            resetValue = 6;
            randomMin = 5;
            randomMax = 9;
        }
        // 飞侠/暗夜行者（敏捷系职业）
        else if (job.isA(JobEnum.THIEF) || job.isA(JobEnum.NIGHTWALKER1)) {
            baseValue = 16;
            resetValue = 16;
            randomMin = 14;
            randomMax = 18;
        }
        // 弓箭手/风灵使者（远程职业）
        else if (job.isA(JobEnum.BOWMAN) || job.isA(JobEnum.WINDARCHER1)) {
            baseValue = 16;
            resetValue = 16;
            randomMin = 14;
            randomMax = 18;
        }
        // 海盗/冲锋队长（力量系职业）
        else if (job.isA(JobEnum.PIRATE) || job.isA(JobEnum.THUNDERBREAKER1)) {
            baseValue = 18;
            resetValue = 18;
            randomMin = 16;
            randomMax = 20;
            skillId = job.isA(JobEnum.PIRATE) ?
                    Brawler.IMPROVE_MAX_HP :
                    ThunderBreaker.IMPROVE_MAX_HP;
        }

        //========== 技能加成处理 ==========//
        /* 战士/海盗职业在非重置时有技能加成 */
        if (!usedAPReset) {
            if(skillId != null) {
                Skill hpSkill = SkillFactory.getSkill(skillId);
                int skillLevel = player.getSkillLevel(hpSkill);

                if (skillLevel > 0) {
                    // 添加技能效果的Y值（HP增加量）
                    MaxHP += hpSkill.getEffect(skillLevel).getY();
                }
            }
        } else {
            MaxHP += resetValue;    //基于洗血卷轴基础增加血量
        }

        //========== HP基础值计算 ==========//
        if (useRandomizeHpmpGain && usedAPReset) {
            // 随机模式：使用随机范围值
            MaxHP += Randomizer.rand(randomMin, randomMax);
        } else {
            // 固定模式：使用基础值或重置值
            MaxHP += usedAPReset ? resetValue : baseValue;
        }

        return MaxHP;  // 返回最终计算结果
    }

    /**
     * 计算MP增加量（根据职业不同有不同成长）
     * 处理流程：
     * 1. 初始化默认MP增长参数
     * 2. 根据职业类型设置特定参数
     * 3. 处理魔法师职业的技能加成
     * 4. 根据配置计算最终MP增加值
     *
     * @param player 角色对象
     * @param usedAPReset 是否来自AP重置操作
     * @return MP增加量
     */
    private static int calcMpChange(Character player, boolean usedAPReset) {
        reloadConfig();  // 重新加载最新配置参数

        //========== 基础参数初始化 ==========//
        JobEnum job = player.getJob();          // 获取当前职业
        int playerInt = player.getInt();    // 缓存智力值（减少方法调用开销）
        int MaxMP = 0;                      // 最终MP增加值

        //========== 默认配置（适用于新手等未定义职业） ==========//
        int baseValue = 6;       // 基础增加值
        int resetValue = 6;      // AP重置时增加值
        int randomMin = 4;       // 随机最小值
        int randomMax = 6;       // 随机最大值
        float intFactor = 0.5f;  // 智力系数（10%）
        boolean hasSkill = false;// 是否检查技能加成

        //========== 职业专属配置 ==========//
        // 战士/黎明战士/战神（物理系职业）
        if (job.isA(JobEnum.WARRIOR) || job.isA(JobEnum.DAWNWARRIOR1) || job.isA(JobEnum.ARAN1)) {
            baseValue = 3;       // 标准模式下+3
            resetValue = 2;      // 重置时+2
            randomMin = 2;       // 随机范围2-4
            randomMax = 4;
            intFactor = 0.1f;    // 10%智力加成
        }
        // 魔法师/烈焰巫师（法系职业）
        else if (job.isA(JobEnum.MAGICIAN) || job.isA(JobEnum.BLAZEWIZARD1)) {
            baseValue = 18;      // 标准模式下+18
            resetValue = 18;     // 重置时保持+18
            randomMin = 12;      // 随机范围12-16
            randomMax = 16;
            intFactor = 0.05f;  // 5%智力加成
            hasSkill = true;     // 需要检查技能加成
        }
        // 弓箭手/风灵使者（敏捷远程）
        else if (job.isA(JobEnum.BOWMAN) || job.isA(JobEnum.WINDARCHER1)) {
            baseValue = 10;      // 标准+10
            resetValue = 10;     // 重置+10
            randomMin = 6;       // 随机6-8
            randomMax = 8;
        }
        // 飞侠/暗夜行者（敏捷近战）
        else if (job.isA(JobEnum.THIEF) || job.isA(JobEnum.NIGHTWALKER1)) {
            baseValue = 10;      // 配置同弓箭手
            resetValue = 10;
            randomMin = 6;
            randomMax = 8;
        }
        // 海盗/冲锋队长（力量型）
        else if (job.isA(JobEnum.PIRATE) || job.isA(JobEnum.THUNDERBREAKER1)) {
            baseValue = 14;      // 标准+14
            resetValue = 14;     // 重置+14
            randomMin = 7;       // 随机7-9
            randomMax = 9;
        }

        //========== 技能加成处理 ==========//
        /* 仅魔法师系职业在非重置时有技能加成 */
        if (!usedAPReset && hasSkill) {
            // 根据子职业选择正确的技能ID
            int skillId = job.isA(JobEnum.BLAZEWIZARD1) ?
                    BlazeWizard.INCREASING_MAX_MP :
                    Magician.IMPROVED_MAX_MP_INCREASE;

            Skill mpSkill = SkillFactory.getSkill(skillId);
            int skillLevel = player.getSkillLevel(mpSkill);

            if (skillLevel > 0) {
                // 添加技能效果的Y值（MP增加量）
                MaxMP += mpSkill.getEffect(skillLevel).getY();
            }
        }

        //========== MP基础值计算 ==========//
        if (useRandomizeHpmpGain && usedAPReset) {
            // 随机模式：基础随机值 + 智力系数加成
            MaxMP += Randomizer.rand(randomMin, randomMax) + (int)(playerInt * intFactor);
        } else {
            // 固定模式：使用基础值或重置值
            MaxMP += usedAPReset ? resetValue : baseValue;
        }

        return MaxMP;  // 返回最终计算结果
    }
    /**
     * 获取HP重置时的减少量（职业相关）
     *
     * @param job 职业
     * @return HP减少量
     */
    private static int takeHp(JobEnum job) {
        int MaxHP = 0;

        if (job.isA(JobEnum.WARRIOR) || job.isA(JobEnum.DAWNWARRIOR1) || job.isA(JobEnum.ARAN1)) {
            MaxHP += 54;
        } else if (job.isA(JobEnum.MAGICIAN) || job.isA(JobEnum.BLAZEWIZARD1)) {
            MaxHP += 10;
        } else if (job.isA(JobEnum.THIEF) || job.isA(JobEnum.NIGHTWALKER1)) {
            MaxHP += 20;
        } else if (job.isA(JobEnum.BOWMAN) || job.isA(JobEnum.WINDARCHER1)) {
            MaxHP += 20;
        } else if (job.isA(JobEnum.PIRATE) || job.isA(JobEnum.THUNDERBREAKER1)) {
            MaxHP += 42;
        } else {
            MaxHP += 12;
        }

        return MaxHP;
    }
    /**
     * 获取MP重置时的减少量（职业相关）
     *
     * @param job 职业
     * @return MP减少量
     */
    private static int takeMp(JobEnum job) {
        int MaxMP = 0;

        if (job.isA(JobEnum.WARRIOR) || job.isA(JobEnum.DAWNWARRIOR1) || job.isA(JobEnum.ARAN1)) {
            MaxMP += 4;
        } else if (job.isA(JobEnum.MAGICIAN) || job.isA(JobEnum.BLAZEWIZARD1)) {
            MaxMP += 31;
        } else if (job.isA(JobEnum.BOWMAN) || job.isA(JobEnum.WINDARCHER1)) {
            MaxMP += 12;
        } else if (job.isA(JobEnum.THIEF) || job.isA(JobEnum.NIGHTWALKER1)) {
            MaxMP += 12;
        } else if (job.isA(JobEnum.PIRATE) || job.isA(JobEnum.THUNDERBREAKER1)) {
            MaxMP += 16;
        } else {
            MaxMP += 8;
        }

        return MaxMP;
    }

}
