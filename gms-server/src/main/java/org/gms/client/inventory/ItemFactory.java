/*
 This file is part of the OdinMS Maple Story Server
 Copyright (C) 2008 Patrick Huy <patrick.huy@frz.cc>
 Matthias Butz <matze@odinms.de>
 Jan Christian Meyer <vimes@odinms.de>

 This program is free software: you can redistribute it and/or modify
 it under the terms of the GNU Affero General Public License version 3
 as published by the Free Software Foundation. You may not use, modify
 or distribute this program under any other version of the
 GNU Affero General Public License.

 This program is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU Affero General Public License for more details.

 You should have received a copy of the GNU Affero General Public License
 along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.gms.client.inventory;

import org.gms.client.character.Stat;
import org.gms.util.DatabaseConnection;
import org.gms.util.Pair;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * @author Flav
 */
public enum ItemFactory {

    INVENTORY(1, false),
    STORAGE(2, true),
    CASH_EXPLORER(3, true),
    CASH_CYGNUS(4, true),
    CASH_ARAN(5, true),
    MERCHANT(6, false),
    CASH_OVERALL(7, true),
    MARRIAGE_GIFTS(8, false),
    DUEY(9, false);
    private final int value;
    private final boolean account;

    private static final int lockCount = 400;
    private static final Lock[] locks = new Lock[lockCount];  // thanks Masterrulax for pointing out a bottleneck issue here

    static {
        for (int i = 0; i < lockCount; i++) {
            locks[i] = new ReentrantLock(true);
        }
    }

    ItemFactory(int value, boolean account) {
        this.value = value;
        this.account = account;
    }

    public int getValue() {
        return value;
    }

    /**
     * 按账号id or 角色id加载物品
     *
     * @param id 账号id or 角色id。注意：如果是仓库，这个id是storageId，而非账号id
     * @param login 是否是已装备
     * @return 物品信息
     * @throws SQLException 查询异常
     */
    public List<Pair<ItemSlot, InventoryType>> loadItems(int id, boolean login) throws SQLException {
        if (value != 6) {
            return loadItemsCommon(id, login);
        } else {
            return loadItemsMerchant(id, login);
        }
    }

    public void saveItems(List<Pair<ItemSlot, InventoryType>> items, int id, Connection con) throws SQLException {
        saveItems(items, null, id, con);
    }

    public void saveItems(List<Pair<ItemSlot, InventoryType>> items, List<Short> bundlesList, int id, Connection con) throws SQLException {
        // thanks Arufonsu, MedicOP, BHB for pointing a "synchronized" bottleneck here

        if (value != 6) {
            saveItemsCommon(items, id, con);
        } else {
            saveItemsMerchant(items, bundlesList, id, con);
        }
    }

    private static ItemSlot loadEquipFromResultSet(ResultSet rs) throws SQLException {
        ItemSlot equipSlot = ItemSlot.equipItem(rs.getInt("itemid"), (short) rs.getInt("position"));
        Equip equip = equipSlot.getEquipInfo();
        equip.setOwner(rs.getString("owner"));
        equip.setStat(Stat.ACCURACY, (short) rs.getInt("acc"));
        equip.setStat(Stat.AVOIDABILITY, (short) rs.getInt("avoid"));
        equip.setStat(Stat.DEX, (short) rs.getInt("dex"));
        equip.setStat(Stat.HANDS, (short) rs.getInt("hands"));
        equip.setStat(Stat.MAX_HP, (short) rs.getInt("hp"));
        equip.setStat(Stat.INT, (short) rs.getInt("int"));
        equip.setStat(Stat.JUMP, (short) rs.getInt("jump"));
        equip.setVicious((short) rs.getInt("vicious"));
        equip.setFlag((short) rs.getInt("flag"));
        equip.setStat(Stat.LUK, (short) rs.getInt("luk"));
        equip.setStat(Stat.M_ATK, (short) rs.getInt("matk"));
        equip.setStat(Stat.M_DEF, (short) rs.getInt("mdef"));
        equip.setStat(Stat.MAX_MP, (short) rs.getInt("mp"));
        equip.setStat(Stat.SPEED, (short) rs.getInt("speed"));
        equip.setStat(Stat.STR, (short) rs.getInt("str"));
        equip.setStat(Stat.P_ATK, (short) rs.getInt("watk"));
        equip.setStat(Stat.P_DEF, (short) rs.getInt("wdef"));
        equip.setEnhancementSlots((byte) rs.getInt("upgradeslots"));
        equip.setEnhancementLevel(rs.getByte("level"));
        equip.setItemExp(rs.getInt("itemexp"));
        equip.setItemLevel(rs.getByte("itemlevel"));
        equip.setExpiration(rs.getLong("expiration"));
        if (equip.getCashInfo() != null) equip.getCashInfo().setGiftFrom(rs.getString("giftFrom"));
        equip.setRingId(rs.getInt("ringid"));

        return equipSlot;
    }

    public static List<Pair<ItemSlot, Integer>> loadEquippedItems(int id, boolean isAccount, boolean login) throws SQLException {
        List<Pair<ItemSlot, Integer>> items = new ArrayList<>();

        StringBuilder query = new StringBuilder();
        query.append("SELECT * FROM ");
        query.append("(SELECT id, accountid FROM characters) AS accountterm ");
        query.append("RIGHT JOIN ");
        query.append("(SELECT * FROM (`inventoryitems` LEFT JOIN `inventoryequipment` USING(`inventoryitemid`))) AS equipterm");
        query.append(" ON accountterm.id=equipterm.characterid ");
        query.append("WHERE accountterm.`");
        query.append(isAccount ? "accountid" : "characterid");
        query.append("` = ?");
        query.append(login ? " AND `inventorytype` = " + InventoryType.EQUIPPED.getType() : "");

        try (Connection con = DatabaseConnection.getConnection()) {
            try (PreparedStatement ps = con.prepareStatement(query.toString())) {
                ps.setInt(1, id);

                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Integer cid = rs.getInt("characterid");
                        items.add(new Pair<>(loadEquipFromResultSet(rs), cid));
                    }
                }
            }
        }

        return items;
    }

    private List<Pair<ItemSlot, InventoryType>> loadItemsCommon(int id, boolean login) throws SQLException {
        List<Pair<ItemSlot, InventoryType>> items = new ArrayList<>();

        try (Connection con = DatabaseConnection.getConnection()) {
            StringBuilder query = new StringBuilder();
            query.append("SELECT * FROM `inventoryitems` LEFT JOIN `inventoryequipment` USING(`inventoryitemid`) WHERE `type` = ? AND `");
            query.append(account ? "accountid" : "characterid").append("` = ?");

            if (login) {
                query.append(" AND `inventorytype` = ").append(InventoryType.EQUIPPED.getType());
            }

            try (PreparedStatement ps = con.prepareStatement(query.toString())) {
                ps.setInt(1, value);
                ps.setInt(2, id);

                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        InventoryType mit = InventoryType.getByType(rs.getByte("inventorytype"));

                        if (mit.equals(InventoryType.EQUIP) || mit.equals(InventoryType.EQUIPPED)) {
                            items.add(new Pair<>(loadEquipFromResultSet(rs), mit));
                        } else {
                            int petid = rs.getInt("petid");
                            if (rs.wasNull()) {
                                petid = -1;
                            }

                            ItemSlot item = new ItemSlot(rs.getInt("itemid"), (byte) rs.getInt("position"), (short) rs.getInt("quantity"), petid);
                            item.setOwner(rs.getString("owner"));
                            item.setExpiration(rs.getLong("expiration"));
                            if (item.getCashInfo() != null) item.getCashInfo().setGiftFrom(rs.getString("giftFrom"));
                            item.setFlag((short) rs.getInt("flag"));
                            items.add(new Pair<>(item, mit));
                        }
                    }
                }
            }
        }
        return items;
    }

    private void saveItemsCommon(List<Pair<ItemSlot, InventoryType>> items, int id, Connection con) throws SQLException {
        Lock lock = locks[id % lockCount];
        lock.lock();
        try {
            // 仅在调用方未开事务时自己管事务，避免 DELETE 已提交但 INSERT 失败导致物品丢失。
            // Character.saveCharToDB 已 setAutoCommit(false)，此处不接管；其他调用方
            // （如 HiredMerchant.saveItems）默认 autoCommit=true，此处包裹事务保证原子性。
            boolean ownTransaction = con.getAutoCommit();
            if (ownTransaction) {
                con.setAutoCommit(false);
            }
            try {
                // SQLite 不支持多表 DELETE：改为两条语句，与调用方事务配合保证原子性
                String idColumn = account ? "accountid" : "characterid";
                try (PreparedStatement psEquip = con.prepareStatement(
                        "DELETE FROM `inventoryequipment` WHERE `inventoryitemid` IN "
                                + "(SELECT `inventoryitemid` FROM `inventoryitems` WHERE `type` = ? AND `" + idColumn + "` = ?)")) {
                    psEquip.setInt(1, value);
                    psEquip.setInt(2, id);
                    psEquip.executeUpdate();
                }
                try (PreparedStatement psItem = con.prepareStatement(
                        "DELETE FROM `inventoryitems` WHERE `type` = ? AND `" + idColumn + "` = ?")) {
                    psItem.setInt(1, value);
                    psItem.setInt(2, id);
                    psItem.executeUpdate();
                }

                try (PreparedStatement psItem = con.prepareStatement("INSERT INTO `inventoryitems` VALUES (NULL, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS)) {
                    if (!items.isEmpty()) {
                        for (Pair<ItemSlot, InventoryType> pair : items) {
                            ItemSlot item = pair.getLeft();
                            InventoryType mit = pair.getRight();
                            psItem.setInt(1, value);
                            psItem.setString(2, account ? null : String.valueOf(id));
                            psItem.setString(3, account ? String.valueOf(id) : null);
                            psItem.setInt(4, item.getItemId());
                            psItem.setInt(5, mit.getType());
                            psItem.setInt(6, item.getPosition());
                            psItem.setInt(7, item.getQuantity());
                            psItem.setString(8, item.getOwner());
                            psItem.setInt(9, item.getPetId());      // thanks Daddy Egg for alerting a case of unique petid constraint breach getting raised
                            psItem.setInt(10, item.getFlag());
                            psItem.setLong(11, item.getExpiration());
                            psItem.setString(12, item.getCashInfo() != null ? item.getCashInfo().getGiftFrom() : "");
                            psItem.executeUpdate();

                            if (mit.equals(InventoryType.EQUIP) || mit.equals(InventoryType.EQUIPPED)) {
                                try (PreparedStatement psEquip = con.prepareStatement("INSERT INTO `inventoryequipment` VALUES (NULL, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                                    try (ResultSet rs = psItem.getGeneratedKeys()) {
                                        if (!rs.next()) {
                                            throw new RuntimeException("Inserting item failed.");
                                        }

                                        psEquip.setInt(1, rs.getInt(1));
                                    }

                                    Equip equip = item.getEquipInfo();
                                    psEquip.setInt(2, equip.getEnhancementSlots());
                                    psEquip.setInt(3, equip.getEnhancementLevel());
                                    psEquip.setInt(4, equip.getStat(Stat.STR));
                                    psEquip.setInt(5, equip.getStat(Stat.DEX));
                                    psEquip.setInt(6, equip.getStat(Stat.INT));
                                    psEquip.setInt(7, equip.getStat(Stat.LUK));
                                    psEquip.setInt(8, equip.getStat(Stat.MAX_HP));
                                    psEquip.setInt(9, equip.getStat(Stat.MAX_MP));
                                    psEquip.setInt(10, equip.getStat(Stat.P_ATK));
                                    psEquip.setInt(11, equip.getStat(Stat.M_ATK));
                                    psEquip.setInt(12, equip.getStat(Stat.P_DEF));
                                    psEquip.setInt(13, equip.getStat(Stat.M_DEF));
                                    psEquip.setInt(14, equip.getStat(Stat.ACCURACY));
                                    psEquip.setInt(15, equip.getStat(Stat.AVOIDABILITY));
                                    psEquip.setInt(16, equip.getStat(Stat.HANDS));
                                    psEquip.setInt(17, equip.getStat(Stat.SPEED));
                                    psEquip.setInt(18, equip.getStat(Stat.JUMP));
                                    psEquip.setInt(19, 0);
                                    psEquip.setInt(20, equip.getVicious());
                                    psEquip.setInt(21, equip.getItemLevel());
                                    psEquip.setInt(22, equip.getItemExp());
                                    psEquip.setInt(23, equip.getRingId());
                                    psEquip.executeUpdate();
                                }
                            }
                        }
                    }
                }
                if (ownTransaction) {
                    con.commit();
                }
            } catch (SQLException | RuntimeException e) {
                if (ownTransaction) {
                    try {
                        con.rollback();
                    } catch (SQLException re) {
                        e.addSuppressed(re);
                    }
                }
                throw e;
            } finally {
                if (ownTransaction) {
                    try {
                        con.setAutoCommit(true);
                    } catch (SQLException se) {
                        // ignore restore failure
                    }
                }
            }
        } finally {
            lock.unlock();
        }
    }

    private List<Pair<ItemSlot, InventoryType>> loadItemsMerchant(int id, boolean login) throws SQLException {
        List<Pair<ItemSlot, InventoryType>> items = new ArrayList<>();

        try (Connection con = DatabaseConnection.getConnection()) {
            StringBuilder query = new StringBuilder();
            query.append("SELECT * FROM `inventoryitems` LEFT JOIN `inventoryequipment` USING(`inventoryitemid`) WHERE `type` = ? AND `");
            query.append(account ? "accountid" : "characterid").append("` = ?");

            if (login) {
                query.append(" AND `inventorytype` = ").append(InventoryType.EQUIPPED.getType());
            }

            try (PreparedStatement ps = con.prepareStatement(query.toString())) {
                ps.setInt(1, value);
                ps.setInt(2, id);

                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        short bundles = 0;
                        try (PreparedStatement psBundle = con.prepareStatement("SELECT `bundles` FROM `inventorymerchant` WHERE `inventoryitemid` = ?")) {
                            psBundle.setInt(1, rs.getInt("inventoryitemid"));

                            try (ResultSet rs2 = psBundle.executeQuery()) {
                                if (rs2.next()) {
                                    bundles = rs2.getShort("bundles");
                                }
                            }
                        }

                        InventoryType mit = InventoryType.getByType(rs.getByte("inventorytype"));

                        if (mit.equals(InventoryType.EQUIP) || mit.equals(InventoryType.EQUIPPED)) {
                            items.add(new Pair<>(loadEquipFromResultSet(rs), mit));
                        } else {
                            if (bundles > 0) {
                                int petid = rs.getInt("petid");
                                if (rs.wasNull()) {
                                    petid = -1;
                                }

                                ItemSlot item = new ItemSlot(rs.getInt("itemid"), (byte) rs.getInt("position"), (short) (bundles * rs.getInt("quantity")), petid);
                                item.setOwner(rs.getString("owner"));
                                item.setExpiration(rs.getLong("expiration"));
                                if (item.getCashInfo() != null) item.getCashInfo().setGiftFrom(rs.getString("giftFrom"));
                                item.setFlag((short) rs.getInt("flag"));
                                items.add(new Pair<>(item, mit));
                            }
                        }
                    }
                }
            }
        }
        return items;
    }

    private void saveItemsMerchant(List<Pair<ItemSlot, InventoryType>> items, List<Short> bundlesList, int id, Connection con) throws SQLException {
        Lock lock = locks[id % lockCount];
        lock.lock();
        try {
            // 仅在调用方未开事务时自己管事务，避免 DELETE 已提交但 INSERT 失败导致物品丢失。
            // HiredMerchant.saveItems 默认 autoCommit=true，此处包裹事务保证
            // inventorymerchant + inventoryitems + inventoryequipment 的 DELETE/INSERT 原子提交。
            boolean ownTransaction = con.getAutoCommit();
            if (ownTransaction) {
                con.setAutoCommit(false);
            }
            try {
                try (PreparedStatement ps = con.prepareStatement("DELETE FROM `inventorymerchant` WHERE `characterid` = ?")) {
                    ps.setInt(1, id);
                    ps.executeUpdate();
                }

                // SQLite 不支持多表 DELETE：改为两条语句，与调用方事务配合保证原子性
                String idColumn = account ? "accountid" : "characterid";
                try (PreparedStatement psEquip = con.prepareStatement(
                        "DELETE FROM `inventoryequipment` WHERE `inventoryitemid` IN "
                                + "(SELECT `inventoryitemid` FROM `inventoryitems` WHERE `type` = ? AND `" + idColumn + "` = ?)")) {
                    psEquip.setInt(1, value);
                    psEquip.setInt(2, id);
                    psEquip.executeUpdate();
                }
                try (PreparedStatement psItem = con.prepareStatement(
                        "DELETE FROM `inventoryitems` WHERE `type` = ? AND `" + idColumn + "` = ?")) {
                    psItem.setInt(1, value);
                    psItem.setInt(2, id);
                    psItem.executeUpdate();
                }

                int i = 0;
                for (Pair<ItemSlot, InventoryType> pair : items) {
                    final ItemSlot item = pair.getLeft();
                    final Short bundles = bundlesList.get(i);
                    final InventoryType mit = pair.getRight();
                    i++;

                    final int genKey;
                    // Item
                    try (PreparedStatement ps = con.prepareStatement("INSERT INTO `inventoryitems` VALUES (NULL, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS)) {
                        ps.setInt(1, value);
                        ps.setString(2, account ? null : String.valueOf(id));
                        ps.setString(3, account ? String.valueOf(id) : null);
                        ps.setInt(4, item.getItemId());
                        ps.setInt(5, mit.getType());
                        ps.setInt(6, item.getPosition());
                        ps.setInt(7, item.getQuantity());
                        ps.setString(8, item.getOwner());
                        ps.setInt(9, item.getPetId());
                        ps.setInt(10, item.getFlag());
                        ps.setLong(11, item.getExpiration());
                        ps.setString(12, item.getCashInfo() != null ? item.getCashInfo().getGiftFrom() : "");
                        ps.executeUpdate();

                        try (ResultSet rs = ps.getGeneratedKeys()) {
                            if (!rs.next()) {
                                throw new RuntimeException("Inserting item failed.");
                            }

                            genKey = rs.getInt(1);
                        }
                    }

                    // Merchant
                    try (PreparedStatement ps = con.prepareStatement("INSERT INTO `inventorymerchant` VALUES (NULL, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS)) {
                        ps.setInt(1, genKey);
                        ps.setInt(2, id);
                        ps.setInt(3, bundles);
                        ps.executeUpdate();
                    }

                    // Equipment
                    if (mit.equals(InventoryType.EQUIP) || mit.equals(InventoryType.EQUIPPED)) {
                        try (PreparedStatement ps = con.prepareStatement("INSERT INTO `inventoryequipment` VALUES (NULL, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                            ps.setInt(1, genKey);

                            Equip equip = item.getEquipInfo();
                            ps.setInt(2, equip.getEnhancementSlots());
                            ps.setInt(3, equip.getEnhancementLevel());
                            ps.setInt(4, equip.getStat(Stat.STR));
                            ps.setInt(5, equip.getStat(Stat.DEX));
                            ps.setInt(6, equip.getStat(Stat.INT));
                            ps.setInt(7, equip.getStat(Stat.LUK));
                            ps.setInt(8, equip.getStat(Stat.MAX_HP));
                            ps.setInt(9, equip.getStat(Stat.MAX_MP));
                            ps.setInt(10, equip.getStat(Stat.P_ATK));
                            ps.setInt(11, equip.getStat(Stat.M_ATK));
                            ps.setInt(12, equip.getStat(Stat.P_DEF));
                            ps.setInt(13, equip.getStat(Stat.M_DEF));
                            ps.setInt(14, equip.getStat(Stat.ACCURACY));
                            ps.setInt(15, equip.getStat(Stat.AVOIDABILITY));
                            ps.setInt(16, equip.getStat(Stat.HANDS));
                            ps.setInt(17, equip.getStat(Stat.SPEED));
                            ps.setInt(18, equip.getStat(Stat.JUMP));
                            ps.setInt(19, 0);
                            ps.setInt(20, equip.getVicious());
                            ps.setInt(21, equip.getItemLevel());
                            ps.setInt(22, equip.getItemExp());
                            ps.setInt(23, equip.getRingId());
                            ps.executeUpdate();
                        }
                    }
                }
                if (ownTransaction) {
                    con.commit();
                }
            } catch (SQLException | RuntimeException e) {
                if (ownTransaction) {
                    try {
                        con.rollback();
                    } catch (SQLException re) {
                        e.addSuppressed(re);
                    }
                }
                throw e;
            } finally {
                if (ownTransaction) {
                    try {
                        con.setAutoCommit(true);
                    } catch (SQLException se) {
                        // ignore restore failure
                    }
                }
            }
        } finally {
            lock.unlock();
        }
    }
}
