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
package org.gms.net.server.channel.handlers;

import org.gms.client.character.Stat;
import org.gms.client.character.Character;
import org.gms.client.Client;
import org.gms.client.inventory.Equip;
import org.gms.client.inventory.InventoryTab;
import org.gms.client.inventory.InventoryType;
import org.gms.client.inventory.ItemSlot;
import org.gms.client.inventory.manipulator.InventoryManipulator;
import org.gms.constants.inventory.ItemConstants;
import org.gms.net.AbstractPacketHandler;
import org.gms.net.packet.InPacket;
import org.gms.net.packet.Packet;
import org.gms.net.server.Server;
import org.gms.net.server.channel.Channel;
import org.gms.server.CashShop;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.gms.server.ItemInformationProvider;
import org.gms.server.MTSItemInfo;
import org.gms.util.DatabaseConnection;
import org.gms.util.PacketCreator;
import org.gms.util.Pair;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public final class MTSHandler extends AbstractPacketHandler {
    private static final Logger log = LoggerFactory.getLogger(MTSHandler.class);

    @Override
    public void handlePacket(InPacket p, Client c) {
        // TODO add karma-to-untradeable flag on sold items here

        if (!c.getPlayer().getCashShop().isOpened()) {
            return;
        }
        if (p.available() > 0) {
            byte op = p.readByte();
            switch (op) {
            case 2: { //put item up for sale
                byte itemtype = p.readByte();
                int itemid = p.readInt();
                p.readShort();
                p.skip(7);
                short stars = 1;
                if (itemtype == 1) {
                    p.skip(32);
                } else {
                    stars = p.readShort();
                }
                p.readString(); // another useless thing (owner)
                if (itemtype == 1) {
                    p.skip(32);
                } else {
                    p.readShort();
                }
                short slot;
                short quantity;
                if (itemtype != 1) {
                    if (itemid / 10000 == 207 || itemid / 10000 == 233) {
                        p.skip(8);
                    }
                    slot = (short) p.readInt();
                } else {
                    slot = (short) p.readInt();
                }
                if (itemtype != 1) {
                    if (itemid / 10000 == 207 || itemid / 10000 == 233) {
                        quantity = stars;
                        p.skip(4);
                    } else {
                        quantity = (short) p.readInt();
                    }
                } else {
                    quantity = (byte) p.readInt();
                }
                int price = p.readInt();
                if (itemtype == 1) {
                    quantity = 1;
                }
                if (quantity < 0 || price < 110) {
                    return;
                }
                InventoryType invType = ItemConstants.getInventoryType(itemid);
                // 先查挂单数，不过10才继续（避免扣完物品后再回滚）
                int currentListings;
                try (Connection con = DatabaseConnection.getConnection();
                        PreparedStatement ps = con.prepareStatement("SELECT COUNT(*) FROM mts_items WHERE seller = ?")) {
                    ps.setInt(1, c.getPlayer().getId());
                    ResultSet rs = ps.executeQuery();
                    currentListings = (rs.next()) ? rs.getInt(1) : 0;
                } catch (SQLException e) {
                    e.printStackTrace();
                    return;
                }
                if (currentListings > 10) {
                    c.getPlayer().dropMessage(1, "You already have 10 items up for auction!");
                    c.sendPacket(getMTS(1, 0, 0));
                    c.sendPacket(PacketCreator.transferInventory(getTransfer(c.getPlayer().getId())));
                    c.sendPacket(PacketCreator.notYetSoldInv(getNotYetSold(c.getPlayer().getId())));
                    return;
                }

                InventoryTab inv = c.getPlayer().getInventory(invType);
                inv.lockInventory();
                ItemSlot i;
                try {
                    ItemSlot checkItem = inv.getItem(slot);
                    if (checkItem == null || checkItem.getItemId() != itemid || c.getPlayer().getItemQuantity(itemid, false) < quantity) {
                        return;
                    }
                    if (c.getPlayer().getMeso() < 5000) {
                        return;
                    }
                    i = checkItem.copy();
                    InventoryManipulator.removeFromSlot(c, invType, slot, quantity, false);
                } finally {
                    inv.unlockInventory();
                }
                if (i != null) {
                    try (Connection con = DatabaseConnection.getConnection()) {

                        LocalDate now = LocalDate.now();
                        LocalDate sellEnd = now.plusDays(7);
                        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
                        String date = sellEnd.format(formatter);

                        if (!i.getInventoryType().equals(InventoryType.EQUIP)) {
                            ItemSlot item = i;
                            try (PreparedStatement pse = con.prepareStatement("INSERT INTO mts_items (tab, type, itemid, quantity, expiration, giftFrom, seller, price, owner, sellername, sell_ends) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                                pse.setInt(1, 1);
                                pse.setInt(2, invType.getType());
                                pse.setInt(3, item.getItemId());
                                pse.setInt(4, quantity);
                                pse.setLong(5, item.getExpiration());
                                pse.setString(6, item.getCashInfo() != null ? item.getCashInfo().getGiftFrom() : "");
                                pse.setInt(7, c.getPlayer().getId());
                                pse.setInt(8, price);
                                pse.setString(9, item.getOwner());
                                pse.setString(10, c.getPlayer().getName());
                                pse.setString(11, date);
                                pse.executeUpdate();
                            }
                        } else {
                            Equip equip = i.getEquipInfo();
                            try (PreparedStatement pse = con.prepareStatement("INSERT INTO mts_items (tab, type, itemid, quantity, expiration, giftFrom, seller, price, upgradeslots, level, str, dex, `int`, luk, hp, mp, watk, matk, wdef, mdef, acc, avoid, hands, speed, jump, locked, owner, sellername, sell_ends, vicious, flag, itemexp, itemlevel, ringid) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                                pse.setInt(1, 1);
                                pse.setInt(2, invType.getType());
                                pse.setInt(3, equip.getItemId());
                                pse.setInt(4, quantity);
                                pse.setLong(5, i.getExpiration());
                                pse.setString(6, i.getCashInfo() != null ? i.getCashInfo().getGiftFrom() : "");
                                pse.setInt(7, c.getPlayer().getId());
                                pse.setInt(8, price);
                                pse.setInt(9, equip.getEnhancementSlots());
                                pse.setInt(10, equip.getEnhancementLevel());
                                pse.setInt(11, equip.getStat(Stat.STR));
                                pse.setInt(12, equip.getStat(Stat.DEX));
                                pse.setInt(13, equip.getStat(Stat.INT));
                                pse.setInt(14, equip.getStat(Stat.LUK));
                                pse.setInt(15, equip.getStat(Stat.MAX_HP));
                                pse.setInt(16, equip.getStat(Stat.MAX_MP));
                                pse.setInt(17, equip.getStat(Stat.P_ATK));
                                pse.setInt(18, equip.getStat(Stat.M_ATK));
                                pse.setInt(19, equip.getStat(Stat.P_DEF));
                                pse.setInt(20, equip.getStat(Stat.M_DEF));
                                pse.setInt(21, equip.getStat(Stat.ACCURACY));
                                pse.setInt(22, equip.getStat(Stat.AVOIDABILITY));
                                pse.setInt(23, equip.getStat(Stat.HANDS));
                                pse.setInt(24, equip.getStat(Stat.SPEED));
                                pse.setInt(25, equip.getStat(Stat.JUMP));
                                pse.setInt(26, 0);
                                pse.setString(27, i.getOwner());
                                pse.setString(28, c.getPlayer().getName());
                                pse.setString(29, date);
                                pse.setInt(30, equip.getVicious());
                                pse.setInt(31, i.getLegacyFlags());
                                pse.setInt(32, equip.getItemExp());
                                pse.setByte(33, (byte) equip.getItemLevel()); // thanks Jefe for noticing missing itemlevel labels
                                pse.setInt(34, equip.getRingId());
                                pse.executeUpdate();
                            }
                        }

                    } catch (SQLException e) {
                        e.printStackTrace();
                        InventoryManipulator.addFromDrop(c, i, false);
                        return;
                    }
                    c.getPlayer().gainMeso(-5000, false);
                    c.sendPacket(PacketCreator.MTSConfirmSell());
                    c.sendPacket(getMTS(1, 0, 0));
                    c.enableCSActions();
                    c.sendPacket(PacketCreator.transferInventory(getTransfer(c.getPlayer().getId())));
                    c.sendPacket(PacketCreator.notYetSoldInv(getNotYetSold(c.getPlayer().getId())));
                }
                break;
            }
            case 3: //send offer for wanted item
                break;
            case 4: //list wanted item
                p.readInt();
                p.readInt();
                p.readInt();
                p.readShort();
                p.readString();
                break;
            case 5: { //change page
                int tab = p.readInt();
                int type = p.readInt();
                int page = p.readInt();
                c.getPlayer().changePage(page);
                if (tab == 4 && type == 0) {
                    c.sendPacket(getCart(c.getPlayer().getId()));
                } else if (tab == c.getPlayer().getCurrentTab() && type == c.getPlayer().getCurrentType() && c.getPlayer().getSearch() != null) {
                    c.sendPacket(getMTSSearch(tab, type, c.getPlayer().getCi(), c.getPlayer().getSearch(), page));
                } else {
                    c.getPlayer().setSearch(null);
                    c.sendPacket(getMTS(tab, type, page));
                }
                c.getPlayer().changeTab(tab);
                c.getPlayer().changeType(type);
                c.enableCSActions();
                c.sendPacket(PacketCreator.transferInventory(getTransfer(c.getPlayer().getId())));
                c.sendPacket(PacketCreator.notYetSoldInv(getNotYetSold(c.getPlayer().getId())));
                break;
            }
            case 6: { //search
                int tab = p.readInt();
                int type = p.readInt();
                p.readInt();
                int ci = p.readInt();
                String search = p.readString();
                c.getPlayer().setSearch(search);
                c.getPlayer().changeTab(tab);
                c.getPlayer().changeType(type);
                c.getPlayer().changeCI(ci);
                c.enableCSActions();
                c.sendPacket(PacketCreator.enableActions());
                c.sendPacket(getMTSSearch(tab, type, ci, search, c.getPlayer().getCurrentPage()));
                c.sendPacket(PacketCreator.showMTSCash(c.getPlayer()));
                c.sendPacket(PacketCreator.transferInventory(getTransfer(c.getPlayer().getId())));
                c.sendPacket(PacketCreator.notYetSoldInv(getNotYetSold(c.getPlayer().getId())));
                break;
            }
            case 7: { //cancel sale
                int id = p.readInt(); // id of the item
                try (Connection con = DatabaseConnection.getConnection()) {
                    try (PreparedStatement ps = con.prepareStatement("UPDATE mts_items SET transfer = 1 WHERE id = ? AND seller = ?")) {
                        ps.setInt(1, id);
                        ps.setInt(2, c.getPlayer().getId());
                        ps.executeUpdate();
                    }

                    try (PreparedStatement ps = con.prepareStatement("DELETE FROM mts_cart WHERE itemid = ?")) {
                        ps.setInt(1, id);
                        ps.executeUpdate();
                    }
                } catch (SQLException e) {
                    e.printStackTrace();
                }
                c.enableCSActions();
                c.sendPacket(getMTS(c.getPlayer().getCurrentTab(), c.getPlayer().getCurrentType(),
                        c.getPlayer().getCurrentPage()));
                c.sendPacket(PacketCreator.notYetSoldInv(getNotYetSold(c.getPlayer().getId())));
                c.sendPacket(PacketCreator.transferInventory(getTransfer(c.getPlayer().getId())));
                break;
            }
            case 8: { // transfer item from transfer inv.
                int id = p.readInt(); // id of the item
                try (Connection con = DatabaseConnection.getConnection()) {
                    try (PreparedStatement ps = con.prepareStatement("SELECT * FROM mts_items WHERE seller = ? AND transfer = 1  AND id= ? ORDER BY id DESC")) {
                        ps.setInt(1, c.getPlayer().getId());
                        ps.setInt(2, id);
                        ResultSet rs = ps.executeQuery();
                        if (rs.next()) {
                            ItemSlot i;
                            if (rs.getInt("type") != 1) {
                                ItemSlot ii = new ItemSlot(rs.getInt("itemid"), (short) 0, (short) rs.getInt("quantity"));
                                ii.setOwner(rs.getString("owner"));
                                ii.setPosition(c.getPlayer().getInventory(ItemConstants.getInventoryType(rs.getInt("itemid"))).getNextFreeSlot());
                                i = ii.copy();
                            } else {
                                ItemSlot equipSlot = ItemSlot.equipItem(rs.getInt("itemid"), (byte) rs.getInt("position"));
                                Equip equip = equipSlot.getEquipInfo();
                                equipSlot.setOwner(rs.getString("owner"));
                                equip.setStat(Stat.ACCURACY, (short) rs.getInt("acc"));
                                equip.setStat(Stat.AVOIDABILITY, (short) rs.getInt("avoid"));
                                equip.setStat(Stat.DEX, (short) rs.getInt("dex"));
                                equip.setStat(Stat.HANDS, (short) rs.getInt("hands"));
                                equip.setStat(Stat.MAX_HP, (short) rs.getInt("hp"));
                                equip.setStat(Stat.INT, (short) rs.getInt("int"));
                                equip.setStat(Stat.JUMP, (short) rs.getInt("jump"));
                                equip.setStat(Stat.LUK, (short) rs.getInt("luk"));
                                equip.setStat(Stat.M_ATK, (short) rs.getInt("matk"));
                                equip.setStat(Stat.M_DEF, (short) rs.getInt("mdef"));
                                equip.setStat(Stat.MAX_MP, (short) rs.getInt("mp"));
                                equip.setStat(Stat.SPEED, (short) rs.getInt("speed"));
                                equip.setStat(Stat.STR, (short) rs.getInt("str"));
                                equip.setStat(Stat.P_ATK, (short) rs.getInt("watk"));
                                equip.setStat(Stat.P_DEF, (short) rs.getInt("wdef"));
                                equip.setEnhancementSlots((byte) rs.getInt("upgradeslots"));
                                equip.setEnhancementLevel((byte) rs.getInt("level"));
                                equip.setItemLevel(rs.getByte("itemlevel"));
                                equip.setItemExp(rs.getInt("itemexp"));
                                equip.setRingId(rs.getInt("ringid"));
                                equip.setVicious((byte) rs.getInt("vicious"));
                                equipSlot.getItem().setFlagsFromLegacy(rs.getInt("flag"));
                                equipSlot.setExpiration(rs.getLong("expiration"));
                                if (equipSlot.getCashInfo() != null) equipSlot.getCashInfo().setGiftFrom(rs.getString("giftFrom"));   // 非现金装备不携带 giftFrom
                                equipSlot.setPosition(c.getPlayer().getInventory(ItemConstants.getInventoryType(rs.getInt("itemid"))).getNextFreeSlot());
                                i = equipSlot.copy();
                            }
                            try (PreparedStatement pse = con.prepareStatement(
                                    "DELETE FROM mts_items WHERE id = ? AND seller = ? AND transfer = 1")) {
                                pse.setInt(1, id);
                                pse.setInt(2, c.getPlayer().getId());
                                pse.executeUpdate();
                            }
                            InventoryManipulator.addFromDrop(c, i, false);
                            c.enableCSActions();
                            c.sendPacket(getCart(c.getPlayer().getId()));
                            c.sendPacket(getMTS(c.getPlayer().getCurrentTab(), c.getPlayer().getCurrentType(),
                                    c.getPlayer().getCurrentPage()));
                            c.sendPacket(PacketCreator.MTSConfirmTransfer(i.getQuantity(), i.getPosition()));
                            c.sendPacket(PacketCreator.transferInventory(getTransfer(c.getPlayer().getId())));
                        }
                    }
                } catch (SQLException e) {
                    log.error("MTS Transfer error", e);
                }
                break;
            }
            case 9: { //add to cart
                int id = p.readInt(); // id of the item
                try (Connection con = DatabaseConnection.getConnection()) {
                    try (PreparedStatement ps1 = con.prepareStatement("SELECT id FROM mts_items WHERE id = ? AND seller <> ?")) {
                        ps1.setInt(1, id); // Dummy query, prevents adding to cart self owned items
                        ps1.setInt(2, c.getPlayer().getId());
                        try (ResultSet rs1 = ps1.executeQuery()) {
                            if (rs1.next()) {
                                PreparedStatement ps = con.prepareStatement("SELECT cid FROM mts_cart WHERE cid = ? AND itemid = ?");
                                ps.setInt(1, c.getPlayer().getId());
                                ps.setInt(2, id);
                                try (ResultSet rs = ps.executeQuery()) {
                                    if (!rs.next()) {
                                        try (PreparedStatement pse = con.prepareStatement("INSERT INTO mts_cart (cid, itemid) VALUES (?, ?)")) {
                                            pse.setInt(1, c.getPlayer().getId());
                                            pse.setInt(2, id);
                                            pse.executeUpdate();
                                        }
                                    }
                                }
                            }
                        }
                    }
                } catch (SQLException e) {
                    e.printStackTrace();
                }
                c.sendPacket(getMTS(c.getPlayer().getCurrentTab(), c.getPlayer().getCurrentType(), c.getPlayer().getCurrentPage()));
                c.enableCSActions();
                c.sendPacket(PacketCreator.enableActions());
                c.sendPacket(PacketCreator.transferInventory(getTransfer(c.getPlayer().getId())));
                c.sendPacket(PacketCreator.notYetSoldInv(getNotYetSold(c.getPlayer().getId())));
                break;
            }
            case 10: { //delete from cart
                int id = p.readInt(); // id of the item
                try (Connection con = DatabaseConnection.getConnection()) {
                    try (PreparedStatement ps = con.prepareStatement("DELETE FROM mts_cart WHERE itemid = ? AND cid = ?")) {
                        ps.setInt(1, id);
                        ps.setInt(2, c.getPlayer().getId());
                        ps.executeUpdate();
                    }
                } catch (SQLException e) {
                    e.printStackTrace();
                }
                c.sendPacket(getCart(c.getPlayer().getId()));
                c.enableCSActions();
                c.sendPacket(PacketCreator.transferInventory(getTransfer(c.getPlayer().getId())));
                c.sendPacket(PacketCreator.notYetSoldInv(getNotYetSold(c.getPlayer().getId())));
                break;
            }
            case 12: //put item up for auction
                break;
            case 13: //cancel wanted cart thing
                break;
            case 14: //buy auction item now
                break;
            case 16: { //buy
                int id = p.readInt(); // id of the item
                try (Connection con = DatabaseConnection.getConnection();
                        PreparedStatement ps = con.prepareStatement("SELECT * FROM mts_items WHERE id = ? ORDER BY id DESC")) {
                    ps.setInt(1, id);
                    ResultSet rs = ps.executeQuery();
                    if (rs.next()) {
                        int price = rs.getInt("price") + 100 + (int) (rs.getInt("price") * 0.1); // taxes
                        if (c.getPlayer().getCashShop().getCash(CashShop.NX_PREPAID) >= price) { // FIX
                            boolean alwaysnull = true;
                            for (Channel cserv : Server.getInstance().getAllChannels()) {
                                Character victim = cserv.getPlayerStorage().getCharacterById(rs.getInt("seller"));
                                if (victim != null) {
                                    victim.getCashShop().gainCash(4, rs.getInt("price"));
                                    alwaysnull = false;
                                }
                            }
                            if (alwaysnull) {
                                try (PreparedStatement pse = con.prepareStatement("SELECT accountid FROM characters WHERE id = ?")) {
                                    pse.setInt(1, rs.getInt("seller"));
                                    ResultSet rse = pse.executeQuery();
                                    if (rse.next()) {
                                        try (PreparedStatement psee = con.prepareStatement("UPDATE accounts SET nxPrepaid = nxPrepaid + ? WHERE id = ?")) {
                                            psee.setInt(1, rs.getInt("price"));
                                            psee.setInt(2, rse.getInt("accountid"));
                                            psee.executeUpdate();
                                        }
                                    }
                                }
                            }
                            try (PreparedStatement pse = con.prepareStatement("UPDATE mts_items SET seller = ?, transfer = 1 WHERE id = ?")) {
                                pse.setInt(1, c.getPlayer().getId());
                                pse.setInt(2, id);
                                pse.executeUpdate();
                            }
                            try (PreparedStatement pse = con.prepareStatement("DELETE FROM mts_cart WHERE itemid = ?")) {
                                pse.setInt(1, id);
                                pse.executeUpdate();
                            }
                            c.getPlayer().getCashShop().gainCash(4, -price);
                            c.enableCSActions();
                            c.sendPacket(getMTS(c.getPlayer().getCurrentTab(), c.getPlayer().getCurrentType(),c.getPlayer().getCurrentPage()));
                            c.sendPacket(PacketCreator.MTSConfirmBuy());
                            c.sendPacket(PacketCreator.showMTSCash(c.getPlayer()));
                            c.sendPacket(PacketCreator.transferInventory(getTransfer(c.getPlayer().getId())));
                            c.sendPacket(PacketCreator.notYetSoldInv(getNotYetSold(c.getPlayer().getId())));
                            c.sendPacket(PacketCreator.enableActions());
                        } else {
                            c.sendPacket(PacketCreator.MTSFailBuy());
                        }
                    }
                } catch (SQLException e) {
                    e.printStackTrace();
                    c.sendPacket(PacketCreator.MTSFailBuy());
                }
                break;
            }
            case 17: { //buy from cart
                int id = p.readInt(); // id of the item
                try (Connection con = DatabaseConnection.getConnection();
                        PreparedStatement ps = con.prepareStatement("SELECT * FROM mts_items WHERE id = ? ORDER BY id DESC")) {
                    ps.setInt(1, id);
                    ResultSet rs = ps.executeQuery();
                    if (rs.next()) {
                        int price = rs.getInt("price") + 100 + (int) (rs.getInt("price") * 0.1);
                        if (c.getPlayer().getCashShop().getCash(CashShop.NX_PREPAID) >= price) {
                            for (Channel cserv : Server.getInstance().getAllChannels()) {
                                Character victim = cserv.getPlayerStorage().getCharacterById(rs.getInt("seller"));
                                if (victim != null) {
                                    victim.getCashShop().gainCash(CashShop.NX_PREPAID, rs.getInt("price"));
                                } else {
                                    try (PreparedStatement pse = con.prepareStatement("SELECT accountid FROM characters WHERE id = ?")) {
                                        pse.setInt(1, rs.getInt("seller"));
                                        ResultSet rse = pse.executeQuery();
                                        if (rse.next()) {
                                            try (PreparedStatement psee = con.prepareStatement("UPDATE accounts SET nxPrepaid = nxPrepaid + ? WHERE id = ?")) {
                                                psee.setInt(1, rs.getInt("price"));
                                                psee.setInt(2, rse.getInt("accountid"));
                                                psee.executeUpdate();
                                            }
                                        }
                                    }
                                }
                            }
                            try (PreparedStatement pse = con.prepareStatement("UPDATE mts_items SET seller = ?, transfer = 1 WHERE id = ?")) {
                                pse.setInt(1, c.getPlayer().getId());
                                pse.setInt(2, id);
                                pse.executeUpdate();
                            }
                            try (PreparedStatement pse = con.prepareStatement("DELETE FROM mts_cart WHERE itemid = ?")) {
                                pse.setInt(1, id);
                                pse.executeUpdate();
                            }
                            c.getPlayer().getCashShop().gainCash(4, -price);
                            c.sendPacket(getCart(c.getPlayer().getId()));
                            c.enableCSActions();
                            c.sendPacket(PacketCreator.MTSConfirmBuy());
                            c.sendPacket(PacketCreator.showMTSCash(c.getPlayer()));
                            c.sendPacket(PacketCreator.transferInventory(getTransfer(c.getPlayer().getId())));
                            c.sendPacket(PacketCreator.notYetSoldInv(getNotYetSold(c.getPlayer().getId())));
                        } else {
                            c.sendPacket(PacketCreator.MTSFailBuy());
                        }
                    }
                } catch (SQLException e) {
                    e.printStackTrace();
                    c.sendPacket(PacketCreator.MTSFailBuy());
                }
                break;
            }
            default:
                log.warn("Unhandled OP (MTS): {}, packet: {}", op, p);
                break;
            }
        } else {
            c.sendPacket(PacketCreator.showMTSCash(c.getPlayer()));
        }
    }

    public List<MTSItemInfo> getNotYetSold(int cid) {
        List<MTSItemInfo> items = new ArrayList<>();
        try (Connection con = DatabaseConnection.getConnection();
                PreparedStatement ps = con.prepareStatement("SELECT * FROM mts_items WHERE seller = ? AND transfer = 0 ORDER BY id DESC")) {
            ps.setInt(1, cid);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                if (rs.getInt("type") != 1) {
                    ItemSlot i = new ItemSlot(rs.getInt("itemid"), (byte) 0, (short) rs.getInt("quantity"));
                    i.setOwner(rs.getString("owner"));
                    items.add(new MTSItemInfo(i, rs.getInt("price"), rs.getInt("id"), rs.getInt("seller"), rs.getString("sellername"), rs.getString("sell_ends")));
                } else {
                    ItemSlot equipSlot = ItemSlot.equipItem(rs.getInt("itemid"), (byte) rs.getInt("position"));
                    Equip equip = equipSlot.getEquipInfo();
                    equipSlot.setOwner(rs.getString("owner"));
                    equip.setStat(Stat.ACCURACY, (short) rs.getInt("acc"));
                    equip.setStat(Stat.AVOIDABILITY, (short) rs.getInt("avoid"));
                    equip.setStat(Stat.DEX, (short) rs.getInt("dex"));
                    equip.setStat(Stat.HANDS, (short) rs.getInt("hands"));
                    equip.setStat(Stat.MAX_HP, (short) rs.getInt("hp"));
                    equip.setStat(Stat.INT, (short) rs.getInt("int"));
                    equip.setStat(Stat.JUMP, (short) rs.getInt("jump"));
                    equip.setVicious((short) rs.getInt("vicious"));
                    equip.setStat(Stat.LUK, (short) rs.getInt("luk"));
                    equip.setStat(Stat.M_ATK, (short) rs.getInt("matk"));
                    equip.setStat(Stat.M_DEF, (short) rs.getInt("mdef"));
                    equip.setStat(Stat.MAX_MP, (short) rs.getInt("mp"));
                    equip.setStat(Stat.SPEED, (short) rs.getInt("speed"));
                    equip.setStat(Stat.STR, (short) rs.getInt("str"));
                    equip.setStat(Stat.P_ATK, (short) rs.getInt("watk"));
                    equip.setStat(Stat.P_DEF, (short) rs.getInt("wdef"));
                    equip.setEnhancementSlots((byte) rs.getInt("upgradeslots"));
                    equip.setEnhancementLevel((byte) rs.getInt("level"));
                    equipSlot.getItem().setFlagsFromLegacy(rs.getInt("flag"));
                    equip.setItemLevel(rs.getByte("itemlevel"));
                    equip.setItemExp(rs.getInt("itemexp"));
                    equip.setRingId(rs.getInt("ringid"));
                    equipSlot.setExpiration(rs.getLong("expiration"));
                    if (equipSlot.getCashInfo() != null) equipSlot.getCashInfo().setGiftFrom(rs.getString("giftFrom"));   // 非现金装备不携带 giftFrom
                    items.add(new MTSItemInfo(equipSlot, rs.getInt("price"), rs.getInt("id"), rs.getInt("seller"), rs.getString("sellername"), rs.getString("sell_ends")));
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return items;
    }

    public Packet getCart(int cid) {
        List<MTSItemInfo> items = new ArrayList<>();
        int pages = 0;
        try (Connection con = DatabaseConnection.getConnection()) {
            try (PreparedStatement ps = con.prepareStatement("SELECT * FROM mts_cart WHERE cid = ? ORDER BY id DESC")) {
                ps.setInt(1, cid);
                ResultSet rs = ps.executeQuery();
                while (rs.next()) {
                    try (PreparedStatement pse = con.prepareStatement("SELECT * FROM mts_items WHERE id = ?")) {
                        pse.setInt(1, rs.getInt("itemid"));
                        ResultSet rse = pse.executeQuery();
                        if (rse.next()) {
                            if (rse.getInt("type") != 1) {
                                ItemSlot i = new ItemSlot(rse.getInt("itemid"), (short) 0, (short) rse.getInt("quantity"));
                                i.setOwner(rse.getString("owner"));
                                items.add(new MTSItemInfo(i, rse.getInt("price"), rse.getInt("id"),
                                        rse.getInt("seller"), rse.getString("sellername"), rse.getString("sell_ends")));
                            } else {
                                ItemSlot equipSlot = ItemSlot.equipItem(rse.getInt("itemid"), (byte) rse.getInt("position"));
                                Equip equip = equipSlot.getEquipInfo();
                                equipSlot.setOwner(rse.getString("owner"));
                                equip.setStat(Stat.ACCURACY, (short) rse.getInt("acc"));
                                equip.setStat(Stat.AVOIDABILITY, (short) rse.getInt("avoid"));
                                equip.setStat(Stat.DEX, (short) rse.getInt("dex"));
                                equip.setStat(Stat.HANDS, (short) rse.getInt("hands"));
                                equip.setStat(Stat.MAX_HP, (short) rse.getInt("hp"));
                                equip.setStat(Stat.INT, (short) rse.getInt("int"));
                                equip.setStat(Stat.JUMP, (short) rse.getInt("jump"));
                                equip.setVicious((short) rse.getInt("vicious"));
                                equip.setStat(Stat.LUK, (short) rse.getInt("luk"));
                                equip.setStat(Stat.M_ATK, (short) rse.getInt("matk"));
                                equip.setStat(Stat.M_DEF, (short) rse.getInt("mdef"));
                                equip.setStat(Stat.MAX_MP, (short) rse.getInt("mp"));
                                equip.setStat(Stat.SPEED, (short) rse.getInt("speed"));
                                equip.setStat(Stat.STR, (short) rse.getInt("str"));
                                equip.setStat(Stat.P_ATK, (short) rse.getInt("watk"));
                                equip.setStat(Stat.P_DEF, (short) rse.getInt("wdef"));
                                equip.setEnhancementSlots((byte) rse.getInt("upgradeslots"));
                                equip.setEnhancementLevel((byte) rse.getInt("level"));
                                equip.setItemLevel(rs.getByte("itemlevel"));
                                equip.setItemExp(rs.getInt("itemexp"));
                                equip.setRingId(rs.getInt("ringid"));
                                equipSlot.getItem().setFlagsFromLegacy(rs.getInt("flag"));
                                equipSlot.setExpiration(rs.getLong("expiration"));
                                if (equipSlot.getCashInfo() != null) equipSlot.getCashInfo().setGiftFrom(rs.getString("giftFrom"));   // 非现金装备不携带 giftFrom
                                items.add(new MTSItemInfo(equipSlot, rse.getInt("price"), rse.getInt("id"),
                                        rse.getInt("seller"), rse.getString("sellername"), rse.getString("sell_ends")));
                            }
                        }
                    }
                }
            }
            try (PreparedStatement ps = con.prepareStatement("SELECT COUNT(*) FROM mts_cart WHERE cid = ?")) {
                ps.setInt(1, cid);
                ResultSet rs = ps.executeQuery();
                if (rs.next()) {
                    pages = rs.getInt(1) / 16;
                    if (rs.getInt(1) % 16 > 0) {
                        pages += 1;
                    }
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return PacketCreator.sendMTS(items, 4, 0, 0, pages);
    }

    public List<MTSItemInfo> getTransfer(int cid) {
        List<MTSItemInfo> items = new ArrayList<>();
        try (Connection con = DatabaseConnection.getConnection();
                PreparedStatement ps = con.prepareStatement("SELECT * FROM mts_items WHERE transfer = 1 AND seller = ? ORDER BY id DESC")) {
            ps.setInt(1, cid);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                if (rs.getInt("type") != 1) {
                    ItemSlot i = new ItemSlot(rs.getInt("itemid"), (short) 0, (short) rs.getInt("quantity"));
                    i.setOwner(rs.getString("owner"));
                    items.add(new MTSItemInfo(i, rs.getInt("price"), rs.getInt("id"), rs.getInt("seller"), rs.getString("sellername"), rs.getString("sell_ends")));
                } else {
                    ItemSlot equipSlot = ItemSlot.equipItem(rs.getInt("itemid"), (byte) rs.getInt("position"));
                    Equip equip = equipSlot.getEquipInfo();
                    equipSlot.setOwner(rs.getString("owner"));
                    equip.setStat(Stat.ACCURACY, (short) rs.getInt("acc"));
                    equip.setStat(Stat.AVOIDABILITY, (short) rs.getInt("avoid"));
                    equip.setStat(Stat.DEX, (short) rs.getInt("dex"));
                    equip.setStat(Stat.HANDS, (short) rs.getInt("hands"));
                    equip.setStat(Stat.MAX_HP, (short) rs.getInt("hp"));
                    equip.setStat(Stat.INT, (short) rs.getInt("int"));
                    equip.setStat(Stat.JUMP, (short) rs.getInt("jump"));
                    equip.setVicious((short) rs.getInt("vicious"));
                    equip.setStat(Stat.LUK, (short) rs.getInt("luk"));
                    equip.setStat(Stat.M_ATK, (short) rs.getInt("matk"));
                    equip.setStat(Stat.M_DEF, (short) rs.getInt("mdef"));
                    equip.setStat(Stat.MAX_MP, (short) rs.getInt("mp"));
                    equip.setStat(Stat.SPEED, (short) rs.getInt("speed"));
                    equip.setStat(Stat.STR, (short) rs.getInt("str"));
                    equip.setStat(Stat.P_ATK, (short) rs.getInt("watk"));
                    equip.setStat(Stat.P_DEF, (short) rs.getInt("wdef"));
                    equip.setEnhancementSlots((byte) rs.getInt("upgradeslots"));
                    equip.setEnhancementLevel((byte) rs.getInt("level"));
                    equip.setItemLevel(rs.getByte("itemlevel"));
                    equip.setItemExp(rs.getInt("itemexp"));
                    equip.setRingId(rs.getInt("ringid"));
                    equipSlot.getItem().setFlagsFromLegacy(rs.getInt("flag"));
                    equipSlot.setExpiration(rs.getLong("expiration"));
                    if (equipSlot.getCashInfo() != null) equipSlot.getCashInfo().setGiftFrom(rs.getString("giftFrom"));   // 非现金装备不携带 giftFrom
                    items.add(new MTSItemInfo(equipSlot, rs.getInt("price"), rs.getInt("id"), rs.getInt("seller"), rs.getString("sellername"), rs.getString("sell_ends")));
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return items;
    }

    private static Packet getMTS(int tab, int type, int page) {
        List<MTSItemInfo> items = new ArrayList<>();
        int pages = 0;
        try (Connection con = DatabaseConnection.getConnection()) {
            String sql;
            if (type != 0) {
                sql = "SELECT * FROM mts_items WHERE tab = ? AND type = ? AND transfer = 0 ORDER BY id DESC LIMIT ?, 16";
            } else {
                sql = "SELECT * FROM mts_items WHERE tab = ? AND transfer = 0 ORDER BY id DESC LIMIT ?, 16";
            }
            try (PreparedStatement ps = con.prepareStatement(sql)) {
                ps.setInt(1, tab);
                if (type != 0) {
                    ps.setInt(2, type);
                    ps.setInt(3, page * 16);
                } else {
                    ps.setInt(2, page * 16);
                }
                ResultSet rs = ps.executeQuery();
                while (rs.next()) {
                    if (rs.getInt("type") != 1) {
                        ItemSlot i = new ItemSlot(rs.getInt("itemid"), (short) 0, (short) rs.getInt("quantity"));
                        i.setOwner(rs.getString("owner"));
                        items.add(new MTSItemInfo(i, rs.getInt("price"), rs.getInt("id"), rs.getInt("seller"),
                                rs.getString("sellername"), rs.getString("sell_ends")));
                    } else {
                        ItemSlot equipSlot = ItemSlot.equipItem(rs.getInt("itemid"), (byte) rs.getInt("position"));
                        Equip equip = equipSlot.getEquipInfo();
                        equipSlot.setOwner(rs.getString("owner"));
                        equip.setStat(Stat.ACCURACY, (short) rs.getInt("acc"));
                        equip.setStat(Stat.AVOIDABILITY, (short) rs.getInt("avoid"));
                        equip.setStat(Stat.DEX, (short) rs.getInt("dex"));
                        equip.setStat(Stat.HANDS, (short) rs.getInt("hands"));
                        equip.setStat(Stat.MAX_HP, (short) rs.getInt("hp"));
                        equip.setStat(Stat.INT, (short) rs.getInt("int"));
                        equip.setStat(Stat.JUMP, (short) rs.getInt("jump"));
                        equip.setVicious((short) rs.getInt("vicious"));
                        equip.setStat(Stat.LUK, (short) rs.getInt("luk"));
                        equip.setStat(Stat.M_ATK, (short) rs.getInt("matk"));
                        equip.setStat(Stat.M_DEF, (short) rs.getInt("mdef"));
                        equip.setStat(Stat.MAX_MP, (short) rs.getInt("mp"));
                        equip.setStat(Stat.SPEED, (short) rs.getInt("speed"));
                        equip.setStat(Stat.STR, (short) rs.getInt("str"));
                        equip.setStat(Stat.P_ATK, (short) rs.getInt("watk"));
                        equip.setStat(Stat.P_DEF, (short) rs.getInt("wdef"));
                        equip.setEnhancementSlots((byte) rs.getInt("upgradeslots"));
                        equip.setEnhancementLevel((byte) rs.getInt("level"));
                        equip.setItemLevel(rs.getByte("itemlevel"));
                        equip.setItemExp(rs.getInt("itemexp"));
                        equip.setRingId(rs.getInt("ringid"));
                        equipSlot.getItem().setFlagsFromLegacy(rs.getInt("flag"));
                        equipSlot.setExpiration(rs.getLong("expiration"));
                        if (equipSlot.getCashInfo() != null) equipSlot.getCashInfo().setGiftFrom(rs.getString("giftFrom"));   // 非现金装备不携带 giftFrom
                        items.add(new MTSItemInfo(equipSlot, rs.getInt("price"), rs.getInt("id"), rs.getInt("seller"), rs.getString("sellername"), rs.getString("sell_ends")));
                    }
                }
            }
            try (PreparedStatement ps = con.prepareStatement("SELECT COUNT(*) FROM mts_items WHERE tab = ? " + (type != 0 ? "AND type = ?" : "") + " AND transfer = 0")) {
                ps.setInt(1, tab);
                if (type != 0) {
                    ps.setInt(2, type);
                }
                ResultSet rs = ps.executeQuery();
                if (rs.next()) {
                    pages = rs.getInt(1) / 16;
                    if (rs.getInt(1) % 16 > 0) {
                        pages++;
                    }
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return PacketCreator.sendMTS(items, tab, type, page, pages); // resniff
    }

    public Packet getMTSSearch(int tab, int type, int cOi, String search, int page) {
        List<MTSItemInfo> items = new ArrayList<>();
        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        String listaitems = "";
        if (cOi != 0) {
            List<String> retItems = new ArrayList<>();
            for (Pair<Integer, String> itemPair : ii.getAllItems()) {
                if (itemPair.getRight().toLowerCase().contains(search.toLowerCase())) {
                    retItems.add(" itemid=" + itemPair.getLeft() + " OR ");
                }
            }
            listaitems += " AND (";
            if (retItems != null && retItems.size() > 0) {
                for (String singleRetItem : retItems) {
                    listaitems += singleRetItem;
                }
                listaitems += " itemid=0 )";
            }
        } else {
            listaitems = " AND sellername LIKE '%' || '" + search + "' || '%'";
        }
        int pages = 0;
        try (Connection con = DatabaseConnection.getConnection()){
            String sql;
            if (type != 0) {
                sql = "SELECT * FROM mts_items WHERE tab = ? " + listaitems + " AND type = ? AND transfer = 0 ORDER BY id DESC LIMIT ?, 16";
            } else {
                sql = "SELECT * FROM mts_items WHERE tab = ? " + listaitems + " AND transfer = 0 ORDER BY id DESC LIMIT ?, 16";
            }
            try (PreparedStatement ps = con.prepareStatement(sql)) {
                ps.setInt(1, tab);
                if (type != 0) {
                    ps.setInt(2, type);
                    ps.setInt(3, page * 16);
                } else {
                    ps.setInt(2, page * 16);
                }
                ResultSet rs = ps.executeQuery();
                while (rs.next()) {
                    if (rs.getInt("type") != 1) {
                        ItemSlot i = new ItemSlot(rs.getInt("itemid"), (short) 0, (short) rs.getInt("quantity"));
                        i.setOwner(rs.getString("owner"));
                        items.add(new MTSItemInfo(i, rs.getInt("price"), rs.getInt("id"), rs.getInt("seller"), rs.getString("sellername"), rs.getString("sell_ends")));
                    } else {
                        ItemSlot equipSlot = ItemSlot.equipItem(rs.getInt("itemid"), (byte) rs.getInt("position"));
                        Equip equip = equipSlot.getEquipInfo();
                        equipSlot.setOwner(rs.getString("owner"));
                        equip.setStat(Stat.ACCURACY, (short) rs.getInt("acc"));
                        equip.setStat(Stat.AVOIDABILITY, (short) rs.getInt("avoid"));
                        equip.setStat(Stat.DEX, (short) rs.getInt("dex"));
                        equip.setStat(Stat.HANDS, (short) rs.getInt("hands"));
                        equip.setStat(Stat.MAX_HP, (short) rs.getInt("hp"));
                        equip.setStat(Stat.INT, (short) rs.getInt("int"));
                        equip.setStat(Stat.JUMP, (short) rs.getInt("jump"));
                        equip.setVicious((short) rs.getInt("vicious"));
                        equip.setStat(Stat.LUK, (short) rs.getInt("luk"));
                        equip.setStat(Stat.M_ATK, (short) rs.getInt("matk"));
                        equip.setStat(Stat.M_DEF, (short) rs.getInt("mdef"));
                        equip.setStat(Stat.MAX_MP, (short) rs.getInt("mp"));
                        equip.setStat(Stat.SPEED, (short) rs.getInt("speed"));
                        equip.setStat(Stat.STR, (short) rs.getInt("str"));
                        equip.setStat(Stat.P_ATK, (short) rs.getInt("watk"));
                        equip.setStat(Stat.P_DEF, (short) rs.getInt("wdef"));
                        equip.setEnhancementSlots((byte) rs.getInt("upgradeslots"));
                        equip.setEnhancementLevel((byte) rs.getInt("level"));
                        equip.setItemLevel(rs.getByte("itemlevel"));
                        equip.setItemExp(rs.getInt("itemexp"));
                        equip.setRingId(rs.getInt("ringid"));
                        equipSlot.getItem().setFlagsFromLegacy(rs.getInt("flag"));
                        equipSlot.setExpiration(rs.getLong("expiration"));
                        if (equipSlot.getCashInfo() != null) equipSlot.getCashInfo().setGiftFrom(rs.getString("giftFrom"));   // 非现金装备不携带 giftFrom
                        items.add(new MTSItemInfo(equipSlot, rs.getInt("price"), rs.getInt("id"), rs.getInt("seller"), rs.getString("sellername"), rs.getString("sell_ends")));
                    }
                }
            }
            if (type == 0) {
                try (PreparedStatement ps = con.prepareStatement("SELECT COUNT(*) FROM mts_items WHERE tab = ? " + listaitems + " AND transfer = 0")) {
                    ps.setInt(1, tab);
                    if (type != 0) {
                        ps.setInt(2, type);
                    }
                    ResultSet rs = ps.executeQuery();
                    if (rs.next()) {
                        pages = rs.getInt(1) / 16;
                        if (rs.getInt(1) % 16 > 0) {
                            pages++;
                        }
                    }
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return PacketCreator.sendMTS(items, tab, type, page, pages);
    }
}
