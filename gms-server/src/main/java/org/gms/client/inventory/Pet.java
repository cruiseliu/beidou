/*
	This file is part of the OdinMS Maple Story Server
    Copyright (C) 2008 Patrick Huy <patrick.huy@frz.cc>
		       Matthias Butz <matze@odinms.de>
		       Jan Christian Meyer <vimes@odinms.de>

    This program is free software under the GNU Affero General Public License
    version 3 as published by the Free Software Foundation, see LICENSE for details.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/
package org.gms.client.inventory;

import org.gms.client.character.Character;
import org.gms.util.CashIdGenerator;
import org.gms.constants.game.ExpTable;
import org.gms.server.ItemInformationProvider;
import org.gms.server.movement.AbsoluteLifeMovement;
import org.gms.server.movement.LifeMovement;
import org.gms.server.movement.LifeMovementFragment;
import org.gms.util.DatabaseConnection;
import org.gms.util.PacketCreator;
import org.gms.util.Pair;

import java.awt.*;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * 宠物：与物品解耦的独立对象，由角色（CharacterPets）全权管理生命周期。
 * 与宿主物品只靠 petid 关联、互不存引用：
 * - 宠物需要宿主物品时经 owner 遍历 CASH 背包按 petid 匹配（findPetItemSlot）；
 * - 物品需要宠物时经 CharacterPets 的 petid→Pet 映射查询。
 * itemId 是 wz 数据 key（宠物命令/饥饿/可食饲料判定），自持于此。
 */
public class Pet {
    private final Character owner;
    private final int itemId;
    private final int uniqueid;
    private String name;
    private int tameness = 0;
    private byte level = 1;
    private int fullness = 100;
    private int Fh;
    private Point pos;
    private int stance;
    private boolean summoned;
    private int petAttribute = 0;

    public enum PetAttribute {
        OWNER_SPEED(0x01);

        private final int i;

        PetAttribute(int i) {
            this.i = i;
        }

        public int getValue() {
            return i;
        }
    }

    Pet(Character owner, int itemId, int uniqueid) {
        this.owner = owner;
        this.itemId = itemId;
        this.uniqueid = uniqueid;
        this.pos = new Point(0, 0);
    }

    public static Pet loadFromDb(Character owner, int itemid, int petid) {
        Pet ret = new Pet(owner, itemid, petid);
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT name, level, closeness, fullness, summoned, flag FROM pets WHERE petid = ?")) { // Get the pet details...
            ps.setInt(1, petid);

            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                ret.setName(rs.getString("name"));
                ret.setTameness(Math.min(rs.getInt("closeness"), 30000));
                ret.setLevel((byte) Math.min(rs.getByte("level"), 30));
                ret.setFullness(Math.min(rs.getInt("fullness"), 100));
                ret.setSummoned(rs.getInt("summoned") == 1);
                ret.setPetAttribute(rs.getInt("flag"));
            }
            return ret;
        } catch (SQLException e) {
            e.printStackTrace();
            return null;
        }
    }

    public static void deleteFromDb(Character owner, int petid) {
        try {
            // 宠物基础数据删除后，petignores 会通过外键级联清理，这里同步移除角色内存中的缓存。
            owner.deletePetExcludedData(petid);
            CashIdGenerator.freeCashId(petid);
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    public void saveToDb() {
        try (Connection con = DatabaseConnection.getConnection()) {
            saveToDb(con);
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    /** 用指定连接保存：角色保存主事务内调用，消除第二写者（SQLite 单写者下避免 SQLITE_BUSY） */
    public void saveToDb(Connection con) {
        try (PreparedStatement ps = con.prepareStatement("UPDATE pets SET name = ?, level = ?, closeness = ?, fullness = ?, summoned = ?, flag = ? WHERE petid = ?")) {
            ps.setString(1, getName());
            ps.setInt(2, getLevel());
            ps.setInt(3, getTameness());
            ps.setInt(4, getFullness());
            ps.setInt(5, isSummoned() ? 1 : 0);
            ps.setInt(6, getPetAttribute());
            ps.setInt(7, getUniqueId());
            ps.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public static int createPet(int itemid) {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("INSERT INTO pets (petid, name, level, closeness, fullness, summoned, flag) VALUES (?, ?, 1, 0, 100, 0, 0)")) {
            int ret = CashIdGenerator.generateCashId();
            ps.setInt(1, ret);
            ps.setString(2, ItemInformationProvider.getInstance().getName(itemid));
            ps.executeUpdate();
            return ret;
        } catch (SQLException e) {
            e.printStackTrace();
            return -1;
        }
    }

    public static int createPet(int itemid, byte level, int tameness, int fullness) {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("INSERT INTO pets (petid, name, level, closeness, fullness, summoned, flag) VALUES (?, ?, ?, ?, ?, 0, 0)")) {
            int ret = CashIdGenerator.generateCashId();
            ps.setInt(1, ret);
            ps.setString(2, ItemInformationProvider.getInstance().getName(itemid));
            ps.setByte(3, level);
            ps.setInt(4, tameness);
            ps.setInt(5, fullness);
            ps.executeUpdate();
            return ret;
        } catch (SQLException e) {
            e.printStackTrace();
            return -1;
        }
    }

    public Character getOwner() {
        return owner;
    }

    public int getItemId() {
        return itemId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int getUniqueId() {
        return uniqueid;
    }

    public void setUniqueId(int id) {
        throw new UnsupportedOperationException("petid 不可变（唯一标识，与宿主物品的关联键）");
    }

    public int getTameness() {
        return tameness;
    }

    public void setTameness(int tameness) {
        this.tameness = tameness;
    }

    public byte getLevel() {
        return level;
    }

    public void gainTamenessFullness(Character owner, int incTameness, int incFullness, int type) {
        gainTamenessFullness(owner, incTameness, incFullness, type, false);
    }

    public void gainTamenessFullness(Character owner, int incTameness, int incFullness, int type, boolean forceEnjoy) {
        byte slot = owner.getPetIndex(this);
        boolean enjoyed;

        //will NOT increase pet's tameness if tried to feed pet with 100% fullness
        // unless forceEnjoy == true (cash shop)
        if (fullness < 100 || incFullness == 0 || forceEnjoy) {   //incFullness == 0: command given
            int newFullness = fullness + incFullness;
            if (newFullness > 100) {
                newFullness = 100;
            }
            fullness = newFullness;

            if (incTameness > 0 && tameness < 30000) {
                int newTameness = tameness + incTameness;
                if (newTameness > 30000) {
                    newTameness = 30000;
                }

                tameness = newTameness;
                while (newTameness >= ExpTable.getTamenessNeededForLevel(level)) {
                    level += 1;
                    owner.sendPacket(PacketCreator.showOwnPetLevelUp(slot));
                    owner.getMap().broadcastMessage(PacketCreator.showPetLevelUp(owner, slot));
                }
            }

            enjoyed = true;
        } else {
            int newTameness = tameness - 1;
            if (newTameness < 0) {
                newTameness = 0;
            }

            tameness = newTameness;
            if (level > 1 && newTameness < ExpTable.getTamenessNeededForLevel(level - 1)) {
                level -= 1;
            }

            enjoyed = false;
        }

        owner.getMap().broadcastMessage(PacketCreator.petFoodResponse(owner.getId(), slot, enjoyed, owner.hasPetChatballoon(slot)));
        saveToDb();

        ItemSlot petz = owner.findPetItemSlot(uniqueid);
        if (petz != null) {
            owner.forceUpdateItem(petz);
        }
    }

    public void setLevel(byte level) {
        this.level = level;
    }

    public int getFullness() {
        return fullness;
    }

    public void setFullness(int fullness) {
        this.fullness = fullness;
    }

    public int getFh() {
        return Fh;
    }

    public void setFh(int Fh) {
        this.Fh = Fh;
    }

    public Point getPos() {
        return pos;
    }

    public void setPos(Point pos) {
        this.pos = pos;
    }

    public int getStance() {
        return stance;
    }

    public void setStance(int stance) {
        this.stance = stance;
    }

    public boolean isSummoned() {
        return summoned;
    }

    public void setSummoned(boolean yes) {
        this.summoned = yes;
    }

    public int getPetAttribute() {
        return this.petAttribute;
    }

    private void setPetAttribute(int flag) {
        this.petAttribute = flag;
    }

    public void addPetAttribute(Character owner, PetAttribute flag) {
        this.petAttribute |= flag.getValue();
        saveToDb();

        ItemSlot petz = owner.findPetItemSlot(uniqueid);
        if (petz != null) {
            owner.forceUpdateItem(petz);
        }
    }

    public void removePetAttribute(Character owner, PetAttribute flag) {
        this.petAttribute &= 0xFFFFFFFF ^ flag.getValue();
        saveToDb();

        ItemSlot petz = owner.findPetItemSlot(uniqueid);
        if (petz != null) {
            owner.forceUpdateItem(petz);
        }
    }

    public Pair<Integer, Boolean> canConsume(int itemId) {
        return ItemInformationProvider.getInstance().canPetConsume(this.itemId, itemId);
    }

    public void updatePosition(List<LifeMovementFragment> movement) {
        for (LifeMovementFragment move : movement) {
            if (move instanceof LifeMovement) {
                if (move instanceof AbsoluteLifeMovement) {
                    this.setPos(move.getPosition());
                }
                this.setStance(((LifeMovement) move).getNewstate());
            }
        }
    }
}
