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
package org.gms.client.quest.actions;

import org.gms.client.character.Character;
import org.gms.client.inventory.ItemPool;
import org.gms.client.inventory.ItemStack;
import org.gms.client.inventory.InventoryTransaction;
import org.gms.client.inventory.Item;
import org.gms.client.quest.QuestWz;
import org.gms.client.quest.QuestActionType;
import org.gms.util.AssertUtil;
import org.gms.util.I18nUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.gms.provider.Data;
import org.gms.provider.DataTool;
import org.gms.server.ItemInformationProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * @author Tyler (Twdtwd)
 * @author Ronan
 */
public class ItemAction extends AbstractQuestAction {
    private static final Logger log = LoggerFactory.getLogger(ItemAction.class);

    private List<ItemData> negativeItems = new ArrayList<>();
    private List<ItemData> zeroItems = new ArrayList<>();
    private List<ItemData> positiveItems = new ArrayList<>();
    private List<ItemData> selectItems = new ArrayList<>();
    private List<ItemData> poolItems = new ArrayList<>();

    public ItemAction(QuestWz quest, Data data) {
        super(QuestActionType.ITEM, quest);
        processData_(data);
    }

    private void processData_(Data data) {
        List<ItemData> items = new ArrayList<>();

        for (Data iEntry : data.getChildren()) {
            int id = DataTool.getInt(iEntry.getChildByPath("id"));
            int count = DataTool.getInt(iEntry.getChildByPath("count"), 0);
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

            ItemData item = new ItemData(Integer.parseInt(iEntry.getName()), id, count, prop, job, gender, period);
            items.add(item);
        }

        items.sort((o1, o2) -> o1.map - o2.map);

        for (ItemData item : items) {
            if (item.getProp() == null || item.getProp() == 0) {
                if (item.getCount() < 0) {
                    negativeItems.add(item);
                } else if (item.getCount() > 0) {
                    positiveItems.add(item);
                } else {
                    zeroItems.add(item);
                }
            } else {
                AssertUtil.isTrue(item.getCount() > 0);
                if (item.getProp() > 0) {
                    poolItems.add(item);
                } else {
                    selectItems.add(item);
                }
            }
        }
    }

    private boolean perform(Character chr, int selection, InventoryTransaction tx) {
        for (ItemData item : negativeItems) {
            // "act" is not responsible for "check"
            tx.removeAtMost(item.getId(), -item.getCount());
        }
        for (ItemData item : zeroItems) {
            tx.removeAll(item.getId());
        }

        for (ItemData item : positiveItems) {
            if (canGetItem(item, chr)) {
                tx.add(toItemStack(item));
                AssertUtil.isTrue(item.getPeriod() <= 0);
            }
        }

        if (selection >= 0) {
            int index = -1;
            for (ItemData item : selectItems) {
                if (canGetItem(item, chr)) {
                    index += 1;
                    if (index == selection) {
                        tx.add(toItemStack(item));
                        break;
                    }
                }
            }
            AssertUtil.isTrue(index == selection);
        }

        ItemPool pool = new ItemPool();
        for (ItemData item : poolItems) {
            if (canGetItem(item, chr)) {
                pool.add(toItemStack(item), item.getProp());
            }
        }

        return pool.isEmpty() ? tx.commit() : tx.addPoolAndCommit(pool);
    }

    private ItemStack toItemStack(ItemData actItem) {
        Item item = new Item(actItem.getId());
        if (actItem.getPeriod() > 0) {
            // todo: [refactor]
            // 1. use strand task start time as now
            // 2. register expire task on enter inventory?
            long expire = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(actItem.getPeriod());
            item.setExpiration(expire);
        }
        return new ItemStack(item, actItem.getCount());
    }

    @Override
    public void run(Character chr, Integer extSelection) {
        int selection = extSelection == null ? -1 : extSelection;
        boolean success = perform(chr, selection, chr.getInventory().tryUpdate());
        AssertUtil.isTrue(success);
    }

    @Override
    public boolean check(Character chr, Integer extSelection) {
        int selection = extSelection == null ? -1 : extSelection;
        boolean success = perform(chr, selection, chr.getInventory().testUpdate());
        if (!success) {
            // fixme: [refactor] check official inventory full message
            chr.dropMessage(1, I18nUtil.getMessage("ItemAction.Message1"));
        }
        return success;
    }

    private boolean canGetItem(ItemData item, Character chr) {
        if (item.getGender() != 2 && item.getGender() != chr.getGender()) {
            return false;
        }

        if (item.job > 0) {
            List<Integer> code = getJobBy5ByteEncoding(item.getJob());
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

    public boolean restoreLostItem(Character chr, int itemId) {
        AssertUtil.isTrue(ItemInformationProvider.getInstance().isQuestItem(itemId));

        for (ItemData item : positiveItems) {
            if (item.getId() != itemId) {
                continue;
            }

            boolean success = chr.getInventory().tryUpdate()
                    .removeAll(itemId)
                    .add(toItemStack(item))
                    .commit();
            if (!success) {
                chr.dropMessage(1, I18nUtil.getMessage("ItemAction.Message1"));
            }
            return success;
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
