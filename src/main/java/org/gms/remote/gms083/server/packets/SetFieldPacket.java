package org.gms.remote.gms083.server.packets;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import org.gms.net.opcodes.SendOpcode;
import org.gms.remote.gms083.server.blocks.ItemBlock;
import org.gms.remote.gms083.utils.ByteBufBuilder;
import org.gms.util.StringUtil;

import java.util.List;
import java.util.Map;

/**
 * SET_FIELD（进图主包，v83）。字段 = 基础类型/字符串/字节流/容器（构造时固化，
 * wire 值在 translator 侧换算完毕）；encode 只编码已有字段、零取值逻辑。
 * 依赖方向 translators → packets 单向——本文件的 record 不持任何外部类型，
 * Character 快照与语义查表见 SetFieldTranslator。
 * 子 record 树按 addCharacterInfo 段结构展开（inventory/skills/quests/…），
 * 各段 body() 与 PacketCreator.addXxxInfo 逐字节对齐。
 */
public record SetFieldPacket(
        int channel,            // c.getChannel() - 1
        int[] rng,              // 3 × Randomizer.nextInt()
        int buddyCapacity,
        String linkedName,      // null = 无关联名
        int meso,
        CharStats charStats,
        Inventory inventory,
        Skills skills,
        Quests quests,
        MiniGame miniGame,
        Ring ring,
        Teleport teleport,
        MonsterBook monsterBook,
        NewYear newYear,
        Area area,
        long timestamp          // wire 值（translator 经 Filetimes.toWire 固化）
) implements V83Packet {

    /** 子段通用形态：语义字段 + 自编码（body() 输出纯段内容，无 opcode 前缀） */
    private sealed interface Segment {
        byte[] body();
    }

    @Override
    public SendOpcode opcode() {
        return SendOpcode.SET_FIELD;
    }

    // ── 编码：纯字段重放（与 PacketCreator.getCharInfo 逐字节对齐）──

    @Override
    public ByteBuf encode() {
        ByteBufBuilder p = new ByteBufBuilder();
        p.writeShort((short) opcode().getValue());
        p.writeInt(channel);
        p.writeByte(1);
        p.writeByte(1);
        p.writeShort(0);
        for (int v : rng) {
            p.writeInt(v);
        }
        p.writeLong(-1);
        p.writeByte(0);
        p.writeBytes(charStats.body());
        p.writeByte(buddyCapacity);
        if (linkedName == null) {
            p.writeByte(0);
        } else {
            p.writeByte(1);
            p.writeString(linkedName);
        }
        p.writeInt(meso);
        p.writeBytes(inventory.body());
        p.writeBytes(skills.body());
        p.writeBytes(quests.body());
        p.writeBytes(miniGame.body());
        p.writeBytes(ring.body());
        p.writeBytes(teleport.body());
        p.writeBytes(monsterBook.body());
        p.writeBytes(newYear.body());
        p.writeBytes(area.body());
        p.writeShort(0);
        p.writeLong(timestamp);
        return p.build();
    }

    // ── 子 record 树 ──

    /** addCharStats：id/名字（encode 补齐 13 定长）/外观/宠物位×3/等级/职业/四维/HP·MP/
     *  AP/SP（表职业 remainingSpTable，普通 remainingSp——二选一在 translator 固化）/
     *  exp/名声/gacha/地图/出生点。 */
    public record CharStats(int charId, String name, byte gender, byte skin, int face, int hair,
                            long[] petIds, byte level, short job,
                            short str, short dex, short int_, short luk,
                            short hp, short maxHp, short mp, short maxMp,
                            short remainingAp, Short remainingSp, int[] remainingSpTable,
                            int exp, short fame, int gachaExp, int mapId, byte spawnPoint) implements Segment {

        @Override
        public byte[] body() {
            ByteBufBuilder p = new ByteBufBuilder();
            p.writeInt(charId);
            p.writeFixedString(StringUtil.getRightPaddedStr(name, '\0', 13));
            p.writeByte(gender);
            p.writeByte(skin);
            p.writeInt(face);
            p.writeInt(hair);
            for (long petId : petIds) {
                p.writeLong(petId);
            }
            p.writeByte(level);
            p.writeShort(job);
            p.writeShort(str);
            p.writeShort(dex);
            p.writeShort(int_);
            p.writeShort(luk);
            p.writeShort(hp);
            p.writeShort(maxHp);
            p.writeShort(mp);
            p.writeShort(maxMp);
            p.writeShort(remainingAp);
            if (remainingSpTable != null) {
                p.writeByte(effectiveSpLength(remainingSpTable));
                for (int i = 0; i < remainingSpTable.length; i++) {
                    if (remainingSpTable[i] > 0) {
                        p.writeByte(i + 1);
                        p.writeInt(remainingSpTable[i]);
                    }
                }
            } else {
                p.writeShort(remainingSp);
            }
            p.writeInt(exp);
            p.writeShort(fame);
            p.writeInt(gachaExp);
            p.writeInt(mapId);
            p.writeByte(spawnPoint);
            p.writeInt(0);
            return p.getBytes();
        }

        private static int effectiveSpLength(int[] sps) {
            int n = 0;
            for (int v : sps) {
                if (v > 0) {
                    n++;
                }
            }
            return n;
        }
    }

    /** inventory 条目（构造时固化）：条目体块（{@link ItemBlock}，Item 本体经工厂冻结为
     *  wire 字段）+ wire 位置（equip 类型：abs + >100 剥离现金位——数值换算在构造时完成）。 */
    public record ItemEntry(ItemBlock block, short wirePos) {
    }

    /**
     * addInventoryInfo：槽位上限 ×5 + 基准时间 + 7 个物品列表段（段间分隔符随 encode 直写）。
     * 纯字段 record——Character 背包 → 字段的快照在 SetFieldTranslator。
     * 条目 wire 位置在构造时换算（equip 类型 abs + >100 剥离现金位）；条目体 = ItemBlock
     * （位置已由本段直写，块从 itemType 起编码）。
     */
    public record Inventory(byte[] slotLimits, long baseTime,
                            List<ItemEntry> equipped,
                            List<ItemEntry> equippedCash,
                            List<ItemEntry> equip,
                            List<ItemEntry> use,
                            List<ItemEntry> setup,
                            List<ItemEntry> etc,
                            List<ItemEntry> cash) implements Segment {

        @Override
        public byte[] body() {
            ByteBufBuilder p = new ByteBufBuilder();
            for (byte b : slotLimits) {
                p.writeByte(b);
            }
            p.writeLong(baseTime);   // 基准时间（wire 值，构造时经 Filetimes.toWire 固化）
            writeItemEntries(p, equipped);
            p.writeShort(0);   // start of equip cash
            writeItemEntries(p, equippedCash);
            p.writeShort(0);   // start of equip inventory
            writeItemEntries(p, equip);
            p.writeInt(0);
            writeItemEntries(p, use);
            p.writeByte(0);
            writeItemEntries(p, setup);
            p.writeByte(0);
            writeItemEntries(p, etc);
            p.writeByte(0);
            writeItemEntries(p, cash);
            return ByteBufUtil.getBytes(p.build());
        }

        private static void writeItemEntries(ByteBufBuilder p, List<ItemEntry> entries) {
            for (ItemEntry e : entries) {
                // 位置直写（equip 短、其余字节——原 addItemInfo 的位置语义）+ 条目体（自 itemType 起）
                if (e.block().type() == 1) {
                    p.writeShort(e.wirePos());
                } else {
                    p.writeByte((byte) e.wirePos());
                }
                e.block().encode(p);
            }
        }
    }

    /**
     * addSkillInfo：技能表（隐藏技能过滤与四转 masterLevel 分支在 translator 固化）+
     * 冷却表（secondsLeft 构造时取值——秒级显示值）。expiration 为 wire 值
     * （构造时经 Filetimes.toWire 固化）。
     */
    public record Skills(List<SkillEntry> skills, List<CooldownEntry> cooldowns) implements Segment {
        public record SkillEntry(int skillId, int skillLevel, long expiration, boolean fourthJob, int masterLevel) {
        }

        public record CooldownEntry(int skillId, short secondsLeft) {
        }

        @Override
        public byte[] body() {
            ByteBufBuilder p = new ByteBufBuilder();
            p.writeByte(0);   // start of skills
            p.writeShort(skills().size());
            for (SkillEntry se : skills()) {
                p.writeInt(se.skillId());
                p.writeInt(se.skillLevel());
                p.writeLong(se.expiration());
                if (se.fourthJob()) {
                    p.writeInt(se.masterLevel());
                }
            }
            p.writeShort(cooldowns().size());
            for (CooldownEntry cd : cooldowns()) {
                p.writeInt(cd.skillId());
                p.writeShort(cd.secondsLeft());
            }
            return p.getBytes();
        }
    }

    /** addQuestInfo：进行中条目平铺（原实现的 infoNumber 双写即平铺语义——size 同步计 2）+
     *  已完成（questId + completionTime——wire 值，构造时经 Filetimes.toWire 固化）。 */
    public record Quests(List<StartedQuest> started, List<CompletedQuest> completed) implements Segment {
        public record StartedQuest(int questId, String progressData) {
        }

        public record CompletedQuest(int questId, long completionTime) {
        }

        @Override
        public byte[] body() {
            ByteBufBuilder p = new ByteBufBuilder();
            p.writeShort(started().size());
            for (StartedQuest q : started()) {
                p.writeShort(q.questId());
                p.writeString(q.progressData());
            }
            p.writeShort(completed().size());
            for (CompletedQuest q : completed()) {
                p.writeShort(q.questId());
                p.writeLong(q.completionTime());
            }
            return p.getBytes();
        }
    }

    /** addMiniGameInfo：现状恒 writeShort(0)。 */
    public record MiniGame() implements Segment {
        @Override
        public byte[] body() {
            ByteBufBuilder p = new ByteBufBuilder();
            p.writeShort(0);
            return p.getBytes();
        }
    }

    /** addRingInfo：crush/friendship/marriage 三段（婚约分支在 translator 固化）。 */
    public record Ring(List<RingEntry> crushRings, List<RingEntry> friendshipRings,
                       Marriage marriage) implements Segment {
        public record Marriage(int relationshipId, int selfId, int partnerId, short ringType,
                               int itemId, String selfName, String partnerName) {
        }

        public record RingEntry(int partnerChrId, String partnerName, int ringId, int partnerRingId, int itemId) {
        }

        private static void writeRingList(ByteBufBuilder p, List<RingEntry> rings, boolean withItemId) {
            p.writeShort(rings.size());
            for (RingEntry r : rings) {
                p.writeInt(r.partnerChrId());
                p.writeFixedString(StringUtil.getRightPaddedStr(r.partnerName(), '\0', 13));
                p.writeInt(r.ringId());
                p.writeInt(0);
                p.writeInt(r.partnerRingId());
                if (withItemId) {
                    p.writeInt(r.itemId());
                }
            }
        }

        @Override
        public byte[] body() {
            ByteBufBuilder p = new ByteBufBuilder();
            writeRingList(p, crushRings(), false);
            writeRingList(p, friendshipRings(), true);
            if (marriage() != null) {
                Marriage m = marriage();
                p.writeShort(1);
                p.writeInt(m.relationshipId());
                p.writeInt(m.selfId());
                p.writeInt(m.partnerId());
                p.writeShort(m.ringType());
                p.writeInt(m.itemId());
                p.writeInt(m.itemId());
                p.writeFixedString(StringUtil.getRightPaddedStr(m.selfName(), '\0', 13));
                p.writeFixedString(StringUtil.getRightPaddedStr(m.partnerName(), '\0', 13));
            } else {
                p.writeShort(0);
            }
            return p.getBytes();
        }
    }

    /** addTeleportInfo：5 普通传送图 + 10 VIP 传送图。 */
    public record Teleport(List<Integer> trockMaps, List<Integer> vipTrockMaps) implements Segment {

        @Override
        public byte[] body() {
            ByteBufBuilder p = new ByteBufBuilder();
            for (int i = 0; i < 5; i++) {
                p.writeInt(trockMaps().get(i));
            }
            for (int i = 0; i < 10; i++) {
                p.writeInt(vipTrockMaps().get(i));
            }
            return p.getBytes();
        }
    }

    /** addMonsterBookInfo：cover + cards（size + 逐项 short id%10000 + byte level）。 */
    public record MonsterBook(int cover, Map<Integer, Integer> cards) implements Segment {

        @Override
        public byte[] body() {
            ByteBufBuilder p = new ByteBufBuilder();
            p.writeInt(cover());
            p.writeByte(0);
            p.writeShort(cards().size());
            for (var e : cards().entrySet()) {
                p.writeShort(e.getKey() % 10000);   // Id
                p.writeByte(e.getValue());          // Level
            }
            return p.getBytes();
        }
    }

    /** addNewYearInfo：卡片条目（encodeNewYearCard 结构，model 记录在 translator 展开）。 */
    public record NewYear(List<Entry> received) implements Segment {
        /** 纯字段条目：NewYearCardRecord 的展开视图（字段序 = encodeNewYearCard 序） */
        public record Entry(int id, int senderId, String senderName, boolean senderDiscard,
                            long dateSent, int receiverId, String receiverName,
                            boolean receiverDiscard, boolean receiverReceived,
                            long dateReceived, String message) {
        }

        @Override
        public byte[] body() {
            ByteBufBuilder p = new ByteBufBuilder();
            p.writeShort(received().size());
            for (Entry nyc : received()) {
                p.writeInt(nyc.id());
                p.writeInt(nyc.senderId());
                p.writeString(nyc.senderName());
                p.writeBool(nyc.senderDiscard());
                p.writeLong(nyc.dateSent());
                p.writeInt(nyc.receiverId());
                p.writeString(nyc.receiverName());
                p.writeBool(nyc.receiverDiscard());
                p.writeBool(nyc.receiverReceived());
                p.writeLong(nyc.dateReceived());
                p.writeString(nyc.message());
            }
            return p.getBytes();
        }
    }

    /** addAreaInfo：Map<Short,String> areaInfos（size + 逐项 short key + string）。 */
    public record Area(List<AreaEntry> entries) implements Segment {
        public record AreaEntry(short key, String value) {
        }

        @Override
        public byte[] body() {
            ByteBufBuilder p = new ByteBufBuilder();
            p.writeShort(entries().size());
            for (AreaEntry e : entries()) {
                p.writeShort(e.key());
                p.writeString(e.value());
            }
            return p.getBytes();
        }
    }

    /**
     * warp 变体（玩家换图主包，ChangeMapServerEvent 的 wire 形态）：与登录全量帧
     * 同 opcode 的轻量帧。coordinateArrival = true 时为坐标落地（wire 追加落点 x/y）；
     * timestamp 为 wire 值（Filetimes.toWire 换算在 route 完成）。
     */
    public record Warp(int channel, int mapId, int spawnPoint, int hp,
                       boolean coordinateArrival, int spawnX, int spawnY,
                       long timestamp) implements V83Packet {

        @Override
        public SendOpcode opcode() {
            return SendOpcode.SET_FIELD;
        }

        @Override
        public ByteBuf encode() {
            ByteBufBuilder p = new ByteBufBuilder();
            p.writeShort((short) opcode().getValue());
            p.writeInt(channel);
            p.writeInt(0);
            p.writeByte(0);
            p.writeInt(mapId);
            p.writeByte(spawnPoint);
            p.writeShort(hp);
            p.writeBool(coordinateArrival);
            if (coordinateArrival) {
                p.writeInt(spawnX);
                p.writeInt(spawnY);
            }
            p.writeLong(timestamp);
            return p.build();
        }
    }
}
