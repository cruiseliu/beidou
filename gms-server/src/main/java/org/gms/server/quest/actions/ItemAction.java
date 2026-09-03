/*
 This file is part of the OdinMS Maple Story Server
 Copyright (C) 2008 Patrick Huy <patrick.huy@frz.cc>
 Matthias Butz <matze@odinms.de>
 Jan Christian Meyer <vimes@odinms.de>

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
package org.gms.server.quest.actions;

import org.gms.client.character.Character;
import org.gms.client.inventory.ItemPool;
import org.gms.client.inventory.ItemStack;
import org.gms.client.inventory.ItemStackWeight;
import org.gms.client.inventory.InventoryTransaction;
import org.gms.client.inventory.InventoryType;
import org.gms.client.inventory.manipulator.InventoryManipulator;
import org.gms.constants.inventory.ItemConstants;
import org.gms.util.I18nUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.gms.provider.Data;
import org.gms.provider.DataTool;
import org.gms.server.ItemInformationProvider;
import org.gms.server.quest.Quest;
import org.gms.server.quest.QuestActionType;
import org.gms.util.PacketCreator;
import org.gms.util.Randomizer;

import java.util.ArrayList;
import java.util.List;

import static java.util.concurrent.TimeUnit.MINUTES;

/**
 * @author Tyler (Twdtwd)
 * @author Ronan
 */
public class ItemAction extends AbstractQuestAction {
    private static final Logger log = LoggerFactory.getLogger(ItemAction.class);
    List<ItemData> items = new ArrayList<>();

    public ItemAction(Quest quest, Data data) {
        super(QuestActionType.ITEM, quest);
        processData(data);
    }

    @Override
    public void processData(Data data) {
        for (Data iEntry : data.getChildren()) {
            int id = DataTool.getInt(iEntry.getChildByPath("id"));
            int count = DataTool.getInt(iEntry.getChildByPath("count"), 1);
            int period = DataTool.getInt(iEntry.getChildByPath("period"), 0);

            Integer prop = null;
            Data propData = iEntry.getChildByPath("prop");
            if (propData != null) {
                prop = DataTool.getInt(propData);
            }

            int gender = 2;
            if (iEntry.getChildByPath("gender") != null) {
                gender = DataTool.getInt(iEntry.getChildByPath("gender"));
            }

            int job = -1;
            if (iEntry.getChildByPath("job") != null) {
                job = DataTool.getInt(iEntry.getChildByPath("job"));
            }

            items.add(new ItemData(Integer.parseInt(iEntry.getName()), id, count, prop, job, gender, period));
        }

        items.sort((o1, o2) -> o1.map - o2.map);
    }

    @Override
    public void run(Character chr, Integer extSelection) {
        List<ItemData> takeItem = new ArrayList<>();
        List<ItemData> giveItem = new ArrayList<>();

        int props = 0, rndProps = 0, accProps = 0;
        for (ItemData item : items) {
            if (item.getProp() != null && item.getProp() != -1 && canGetItem(item, chr)) {
                props += item.getProp();
            }
        }

        int extNum = 0;
        if (props > 0) {
            rndProps = Randomizer.nextInt(props);
        }
        for (ItemData iEntry : items) {
            if (!canGetItem(iEntry, chr)) {
                continue;
            }

            if (iEntry.getProp() != null) {
                if (iEntry.getProp() == -1) {
                    if (extSelection != extNum++) {
                        continue;
                    }
                } else {
                    accProps += iEntry.getProp();

                    if (accProps <= rndProps) {
                        continue;
                    } else {
                        accProps = Integer.MIN_VALUE;
                    }
                }
            }

            if (iEntry.getCount() < 0) { // Remove Item
                takeItem.add(iEntry);
            } else {                    // Give Item
                giveItem.add(iEntry);
            }
        }

        // must take all needed items before giving others

        for (ItemData iEntry : takeItem) {
            int itemid = iEntry.getId(), count = iEntry.getCount();

            InventoryType type = ItemConstants.getInventoryType(itemid);
            int quantity = count * -1; // Invert

            InventoryManipulator.removeById(chr.getClient(), type, itemid, quantity, true, false);
            chr.sendPacket(PacketCreator.getShowItemGain(itemid, (short) count, true));
        }

        for (ItemData iEntry : giveItem) {
            int itemid = iEntry.getId(), count = iEntry.getCount(), period = iEntry.getPeriod();    // thanks Vcoc for noticing quest milestone item not getting removed from inventory after a while

            InventoryManipulator.REFACTOR6_addById(chr.getClient(), itemid, (short) count, "", -1, period > 0 ? (System.currentTimeMillis() + MINUTES.toMillis(period)) : -1);
            chr.sendPacket(PacketCreator.getShowItemGain(itemid, (short) count, true));
        }
    }

    @Override
    public boolean check(Character chr, Integer extSelection) {
        List<ItemStack> removes = new ArrayList<>();          // 固定收取（count<0）
        List<ItemStack> gains = new ArrayList<>();            // 固定发放（count>0）
        List<ItemStackWeight> poolOptions = new ArrayList<>(); // 随机池（prop>=0）
        List<Integer> allItemids = new ArrayList<>();          // 失败提示用（事务无法定位失败项，整体报告）

        int extNum = 0;
        for (ItemData item : items) {
            if (!canGetItem(item, chr)) {
                continue;
            }

            Integer prop = item.getProp();
            if (prop == null) {
                collectBySign(item.getCount() < 0 ? removes : gains, item.getId(), Math.abs(item.getCount()));
                allItemids.add(item.getId());
            } else if (prop < 0) {
                if (extSelection != extNum++) {
                    continue;
                }
                // 玩家选择项按数量符号归入收取/发放
                collectBySign(item.getCount() < 0 ? removes : gains, item.getId(), Math.abs(item.getCount()));
                allItemids.add(item.getId());
            } else {
                poolOptions.add(new ItemStackWeight(item.getId(), item.getCount(), prop));
                allItemids.add(item.getId());
            }
        }

        // 先取后给（run 语义），随机池收尾（验全 = roll 样本空间冻结）；
        // testUpdate：commit 不落真，addPoolAndCommit 的 roll 被丢弃，仅返回可行性
        InventoryTransaction tx = chr.getInventory().testUpdate();
        tx.remove(removes).add(gains);
        boolean feasible = poolOptions.isEmpty() ? tx.commit() : tx.addPoolAndCommit(new ItemPool(poolOptions));

        if (!feasible) {
            announceInventoryLimit(allItemids, chr);
            return false;
        }
        return true;
    }

    private static void collectBySign(List<ItemStack> target, int itemId, int count) {
        target.add(ItemStack.fromExternal(itemId, count));
    }

    private void announceInventoryLimit(List<Integer> itemids, Character chr) {
        for (Integer id : itemids) {
            if (ItemInformationProvider.getInstance().isPickupRestricted(id) && chr.haveItemWithId(id, true)) {
                chr.dropMessage(1, "Please check if you already have a similar one-of-a-kind item in your inventory.");
                return;
            }
        }

        chr.dropMessage(1, I18nUtil.getMessage("ItemAction.Message1"));
    }


    private boolean canGetItem(ItemData item, Character chr) {
        if (item.getGender() != 2 && item.getGender() != chr.getGender()) {
            return false;
        }

        if (item.job > 0) {
            final List<Integer> code = getJobBy5ByteEncoding(item.getJob());
            boolean jobFound = false;
            for (int codec : code) {
                if (codec / 100 == chr.getJob().getId() / 100) {
                    jobFound = true;
                    break;
                }
            }
            return jobFound;
        }

        return true;
    }

    public boolean restoreLostItem(Character chr, int itemid) {
        if (!ItemInformationProvider.getInstance().isQuestItem(itemid)) {
            return false;
        }

        // thanks danielktran (MapleHeroesD)
        for (ItemData item : items) {
            if (item.getId() == itemid) {
                int missingQty = item.getCount() - chr.countItem(itemid);
                if (missingQty > 0) {
                    if (!chr.canHold(itemid, missingQty)) {
                        chr.dropMessage(1, I18nUtil.getMessage("ItemAction.Message1"));
                        return false;
                    }

                    chr.getInventory().add(ItemStack.fromExternal(item.getId(), missingQty));
                    log.debug("Chr {} obtained {}x {} from questId {}", chr, itemid, missingQty, questID);
                }
                return true;
            }
        }

        return false;
    }

    private class ItemData {
        private final int map, id, count, job, gender, period;
        private final Integer prop;

        public ItemData(int map, int id, int count, Integer prop, int job, int gender, int period) {
            this.map = map;
            this.id = id;
            this.count = count;
            this.prop = prop;
            this.job = job;
            this.gender = gender;
            this.period = period;
        }

        public int getId() {
            return id;
        }

        public int getCount() {
            return count;
        }

        public Integer getProp() {
            return prop;
        }

        public int getJob() {
            return job;
        }

        public int getGender() {
            return gender;
        }

        public int getPeriod() {
            return period;
        }
    }
} 
