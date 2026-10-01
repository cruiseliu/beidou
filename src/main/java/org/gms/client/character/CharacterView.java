package org.gms.client.character;

import org.gms.client.JobEnum;
import org.gms.client.SkinColor;
import org.gms.client.inventory.ItemSlot;

import java.util.Collection;

/**
 * charlist 视图只读面：登录/选角列表期的角色条目。两个实现：
 * {@link CharacterViewEntry}（不可变快照，charlist 流程的存储/装载形态）与
 * {@link Character}（活体脸——enter-map 等共享包构建器的宽化入参）。
 *
 * <p>消费方显式限定 = charlist 包构建（{@code PacketCreator.addCharEntry} 系）与
 * World/Server 视图存储（accountChars）。方法面 = CHARLIST 包的字段事实，全部只读标量
 * （外观装备以条目副本给出，宠物只出 id 不出实体句柄）——视图持有者不得触达会话可变
 * 状态（组件句柄、发包、域操作）。
 */
public interface CharacterView {

    // ── 身份 ──

    int getId();

    String getName();

    int getAccountId();

    int getWorld();

    int getGender();

    // ── 外观 ──

    SkinColor getSkinColor();

    int getFace();

    int getHair();

    /** 外观装备条目（EQUIPPED 槽位副本；charlist 外观位图用） */
    Collection<ItemSlot> getEquippedItems();

    /** EQUIPPED 槽穿戴校验完成标志（canWearEquipment 快通路判定） */
    boolean isEquippedChecked();

    /** 标记穿戴校验已完成（canWearEquipment 的记忆化；视图唯一显式可变点，行为保真遗留） */
    void markEquippedChecked();

    // ── 状态 ──

    int getLevel();

    JobEnum getJob();

    int getStr();

    int getDex();

    int getInt();

    int getLuk();

    int getHp();

    int getClientMaxHp();

    int getMp();

    int getClientMaxMp();

    int getRemainingAp();

    int getRemainingSp();

    /** SP 表（hasSPTable 职业的多分位剩余 SP，下标 = 分位-1；单分位职业恒空/零表） */
    int[] getRemainingSps();

    int getExp();

    int getFame();

    int getGachaExp();

    int getMapId();

    int getInitialSpawnPoint();

    /** 槽位宠物 id（无宠物 = 0；视图条目装载跳过宠物，恒 0——CHARLIST 宠物段占位） */
    long getPetId(int slot);

    /** 槽位宠物的道具 id（外观装备联动用；无宠物 = 0） */
    int getPetItemId(int slot);

    // ── 排行元信息 ──

    boolean isGM();

    int gmLevel();

    boolean isGmJob();

    int getRank();

    int getRankMove();

    int getJobRank();

    int getJobRankMove();
}
