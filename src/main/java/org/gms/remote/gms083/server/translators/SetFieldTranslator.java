package org.gms.remote.gms083.server.translators;

import org.gms.client.character.Character;
import org.gms.client.inventory.InventoryType;
import org.gms.client.inventory.ItemSlot;
import org.gms.client.quest.Quest;
import org.gms.constants.game.GameConstants;
import org.gms.remote.gms083.server.blocks.ItemBlock;
import org.gms.remote.gms083.server.packets.SetFieldPacket;
import org.gms.util.Randomizer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * SET_FIELD 翻译：Character 全量快照 → 纯字段 record 树。依赖方向 translators → packets
 * 单向——packets 不持 Character/BuddyList/model 等外部类型。语义判定与换算全部在此完成：
 * SP 表/普通分支、隐藏技能过滤与四转 masterLevel、到期 wire 值（{@link Filetimes}）、
 * 婚约名字方向、条目体（{@link ItemBlock} 工厂：可充值 wire 数量 = charge、宠物到期
 * 三分支、equip 查表）与条目 wire 位置（equip 类型 abs + &gt;100 剥离现金位）。
 */
public final class SetFieldTranslator {

    private SetFieldTranslator() {
    }

    /** timestamp 参数为语义毫秒（如 Server.getCurrentTime()），filetime 转换在此固化
     *  （对齐 legacy getCharInfo 的 getTime(getCurrentTime())）。 */
    public static SetFieldPacket setField(Character chr, int channel, int buddyCapacity,
                                          String linkedName, int meso, long timestamp) {
        return new SetFieldPacket(channel,
                new int[]{Randomizer.nextInt(), Randomizer.nextInt(), Randomizer.nextInt()},
                buddyCapacity, linkedName, meso,
                charStats(chr), inventory(chr), skills(chr), quests(chr),
                new SetFieldPacket.MiniGame(), ring(chr), teleport(chr), monsterBook(chr),
                newYear(chr), area(chr),
                Filetimes.toWire(timestamp));
    }

    // ── charStats ──

    private static SetFieldPacket.CharStats charStats(Character chr) {
        boolean hasSpTable = GameConstants.hasSPTable(chr.getJob());
        return new SetFieldPacket.CharStats(
                chr.getId(), chr.getName(), (byte) chr.getGender(), (byte) chr.getSkinColor().getId(),
                chr.getFace(), chr.getHair(),
                new long[]{chr.getPet(0) != null ? chr.getPet(0).getPetId() : 0,
                        chr.getPet(1) != null ? chr.getPet(1).getPetId() : 0,
                        chr.getPet(2) != null ? chr.getPet(2).getPetId() : 0},
                (byte) chr.getLevel(), (short) chr.getJob().getId(),
                (short) chr.getStr(), (short) chr.getDex(), (short) chr.getInt(), (short) chr.getLuk(),
                (short) chr.getHp(), (short) chr.getClientMaxHp(),
                (short) chr.getMp(), (short) chr.getClientMaxMp(),
                (short) chr.getRemainingAp(),
                hasSpTable ? null : (short) chr.getRemainingSp(),
                hasSpTable ? chr.getRemainingSps() : null,
                chr.getExp(), (short) chr.getFame(), chr.getGachaExp(), chr.getMapId(),
                (byte) chr.getInitialSpawnPoint());
    }

    // ── inventory ──

    private static SetFieldPacket.Inventory inventory(Character chr) {
        return new SetFieldPacket.Inventory(slotLimits(chr), Filetimes.toWire(-2),
                entries(chr, splitEquipped(chr).normal()),
                entries(chr, splitEquipped(chr).cash()),
                entries(chr, items(chr, InventoryType.EQUIP)),
                entries(chr, items(chr, InventoryType.USE)),
                entries(chr, items(chr, InventoryType.SETUP)),
                entries(chr, items(chr, InventoryType.ETC)),
                entries(chr, items(chr, InventoryType.CASH)));
    }

    private static byte[] slotLimits(Character chr) {
        byte[] out = new byte[5];
        for (byte i = 1; i <= 5; i++) {
            out[i - 1] = (byte) chr.getInventory(InventoryType.getByType(i)).getSlotLimit();
        }
        return out;
    }

    private record EquippedSplit(List<ItemSlot> normal, List<ItemSlot> cash) {
    }

    private static EquippedSplit splitEquipped(Character chr) {
        var equipped = chr.getInventory(InventoryType.EQUIPPED);
        var normal = new ArrayList<ItemSlot>();
        var cash = new ArrayList<ItemSlot>();
        for (ItemSlot item : equipped.list()) {
            if (item.getPosition() <= -100) {
                cash.add(item);
            } else {
                normal.add(item);
            }
        }
        return new EquippedSplit(normal, cash);
    }

    private static List<ItemSlot> items(Character chr, InventoryType type) {
        return List.copyOf(chr.getInventory(type).list());
    }

    private static List<SetFieldPacket.ItemEntry> entries(Character chr, List<ItemSlot> items) {
        var out = new ArrayList<SetFieldPacket.ItemEntry>(items.size());
        for (var item : items) {
            // equip 位置换算（原 addItemInfo 内联逻辑）：abs + >100 剥离现金位
            short pos = (short) item.getPosition();
            short wirePos = item.getItemType() == 1
                    ? (short) (Math.abs(pos) > 100 ? Math.abs(pos) - 100 : Math.abs(pos))
                    : pos;
            // 宠物槽位（petId 在册）经宿主 Pet 实体冻结（到期三分支/面板截断在工厂完成）；
            // 其余走 Item 工厂（可充值 wire 数量 = charge）
            ItemBlock block = item.getPetId() > -1
                    ? ItemBlock.ofPet(item.getItem(), chr.getPetById(item.getPetId()))
                    : ItemBlock.of(item.getItem(), item.getQuantity());
            out.add(new SetFieldPacket.ItemEntry(block, wirePos));
        }
        return out;
    }

    // ── skills ──

    private static SetFieldPacket.Skills skills(Character chr) {
        var out = new ArrayList<SetFieldPacket.Skills.SkillEntry>();
        for (var e : chr.getSkills().entrySet()) {
            if (GameConstants.isHiddenSkills(e.getKey())) {
                continue;
            }
            var se = e.getValue();
            out.add(new SetFieldPacket.Skills.SkillEntry(e.getKey(), se.skillLevel,
                    Filetimes.toWire(se.expiration),
                    se.skill.isFourthJob(), se.skill.isFourthJob() ? se.masterLevel : 0));
        }
        var cooldowns = new ArrayList<SetFieldPacket.Skills.CooldownEntry>();
        for (var cd : chr.getAllCooldowns()) {
            int timeLeft = (int) (cd.length + cd.startTime - System.currentTimeMillis());
            cooldowns.add(new SetFieldPacket.Skills.CooldownEntry(cd.skillId, (short) (timeLeft / 1000)));
        }
        return new SetFieldPacket.Skills(List.copyOf(out), List.copyOf(cooldowns));
    }

    // ── quests ──

    private static SetFieldPacket.Quests quests(Character chr) {
        var started = new ArrayList<SetFieldPacket.Quests.StartedQuest>();
        for (Quest qs : chr.getStartedQuests()) {
            started.add(new SetFieldPacket.Quests.StartedQuest(qs.getId(),
                    QuestProgressFormat.toWire(qs.getProgress())));
            if (qs.getInfoNumber() > 0) {
                var iqs = chr.getQuest(qs.getInfoNumber());
                started.add(new SetFieldPacket.Quests.StartedQuest(qs.getInfoNumber(),
                        iqs != null ? QuestProgressFormat.toWire(iqs.getProgress()) : ""));
            }
        }
        var completed = new ArrayList<SetFieldPacket.Quests.CompletedQuest>();
        for (Quest qs : chr.getCompletedQuests()) {
            completed.add(new SetFieldPacket.Quests.CompletedQuest(qs.getId(),
                    Filetimes.toWire(qs.getCompletionTime())));
        }
        return new SetFieldPacket.Quests(List.copyOf(started), List.copyOf(completed));
    }

    // ── ring ──

    private static SetFieldPacket.Ring ring(Character chr) {
        return new SetFieldPacket.Ring(
                chr.getCrushRings().stream()
                        .map(r -> new SetFieldPacket.Ring.RingEntry(r.getPartnerChrId(), r.getPartnerName(),
                                r.getRingId(), r.getPartnerRingId(), 0))
                        .toList(),
                chr.getFriendshipRings().stream()
                        .map(r -> new SetFieldPacket.Ring.RingEntry(r.getPartnerChrId(), r.getPartnerName(),
                                r.getRingId(), r.getPartnerRingId(), r.getItemId()))
                        .toList(),
                marriageOf(chr));
    }

    private static SetFieldPacket.Ring.Marriage marriageOf(Character chr) {
        if (chr.getPartnerId() <= 0) {
            return null;
        }
        var marriageRing = chr.getMarriageRing();
        int selfId = chr.getGender() == 0 ? chr.getId() : chr.getPartnerId();
        int partnerId = chr.getGender() == 0 ? chr.getPartnerId() : chr.getId();
        String selfName = chr.getGender() == 0 ? chr.getName() : Character.getNameById(chr.getPartnerId());
        String partnerName = chr.getGender() == 0 ? Character.getNameById(chr.getPartnerId()) : chr.getName();
        int itemId = marriageRing != null ? marriageRing.getItemId() : org.gms.constants.id.ItemId.WEDDING_RING_MOONSTONE;
        return new SetFieldPacket.Ring.Marriage(chr.getRelationshipId(), selfId, partnerId,
                (short) (marriageRing != null ? 3 : 1), itemId, selfName, partnerName);
    }

    // ── teleport / monsterBook / newYear / area ──

    private static SetFieldPacket.Teleport teleport(Character chr) {
        return new SetFieldPacket.Teleport(List.copyOf(chr.getTrockMaps()), List.copyOf(chr.getVipTrockMaps()));
    }

    private static SetFieldPacket.MonsterBook monsterBook(Character chr) {
        return new SetFieldPacket.MonsterBook(chr.getMonsterBookCover(), Map.copyOf(chr.getMonsterBook().getCards()));
    }

    private static SetFieldPacket.NewYear newYear(Character chr) {
        var out = new ArrayList<SetFieldPacket.NewYear.Entry>();
        for (org.gms.model.pojo.NewYearCardRecord nyc : chr.getReceivedNewYearRecords()) {
            out.add(new SetFieldPacket.NewYear.Entry(
                    nyc.getId(), nyc.getSenderId(), nyc.getSenderName(), nyc.isSenderDiscardCard(),
                    nyc.getDateSent(), nyc.getReceiverId(), nyc.getReceiverName(),
                    nyc.isReceiverDiscardCard(), nyc.isReceiverReceivedCard(), nyc.getDateReceived(),
                    nyc.getMessage()));
        }
        return new SetFieldPacket.NewYear(List.copyOf(out));
    }

    private static SetFieldPacket.Area area(Character chr) {
        var out = chr.getAreaInfos().entrySet().stream()
                .map(e -> new SetFieldPacket.Area.AreaEntry(e.getKey(), e.getValue()))
                .toList();
        return new SetFieldPacket.Area(out);
    }
}
