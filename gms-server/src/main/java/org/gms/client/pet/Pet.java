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
package org.gms.client.pet;

import org.gms.client.character.Character;
import org.gms.client.character.CharacterPets;
import org.gms.client.inventory.ItemSlot;
import org.gms.util.CashIdGenerator;
import org.gms.constants.game.ExpTable;
import org.gms.net.server.Server;
import org.gms.server.ItemInformationProvider;
import org.gms.server.movement.AbsoluteLifeMovement;
import org.gms.server.movement.LifeMovement;
import org.gms.server.movement.LifeMovementFragment;
import org.gms.model.json.PetData;
import org.gms.util.DatabaseConnection;
import org.gms.util.Pair;
import org.gms.util.TimeoutHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Point;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 宠物：与物品解耦的独立对象，由角色（CharacterPets）全权管理生命周期。
 * 与宿主物品只靠 petid 关联、互不存引用：
 * - 宠物需要宿主物品时经 owner 遍历 CASH 背包按 petid 匹配（findPetItemSlot）；
 * - 物品需要宠物时经 CharacterPets 的 petid→Pet 映射查询。
 * itemId 是 wz 数据 key（宠物命令/饥饿/可食饲料判定），自持于此。
 */
public class Pet {
    private static final Logger log = LoggerFactory.getLogger(Pet.class);
    private static final ItemInformationProvider ii = ItemInformationProvider.getInstance();

    private static final Map<Integer, Pet> loadedPets = new ConcurrentHashMap<>();

    private static final TimeoutHelper expireTimer = TimeoutHelper.createAndStart((petId, timestamp) -> {
        Pet pet = loadedPets.get(petId);
        if (pet != null) {
            pet.onExpire();
        }
    });

    private CharacterPets owner = null;
    private int petId;

    private int itemId;
    private String name = null;

    private int tameness = 0;
    private int level = 1;
    private int fullness = 100;

    private long expiration = -1;

    /** 到期 epoch 毫秒（-1 = 永久）；协议组装侧经 Item.LEGACY_getExpiration 间接消费 */
    public long getExpiration() {
        return expiration;
    }

    // TODO(debug, 临时): @pet 到期显示探测哨兵（PetCommand debug1/2/3），配合 getTime/toWire 的 -8 临时分支，测完移除
    /** debug1：wire = 现在 - 30 天（过去日期，客户端显示待测） */
    public static final long DEBUG_EXPIRE_PAST = -6;
    /** debug2：wire = 现在 + 30 天（近未来基线） */
    public static final long DEBUG_EXPIRE_FUTURE = -7;
    /** debug3：wire = PERMANENT + 12h（2078-12-31T12:00，超过 PERMANENT、低于 DEFAULT_TIME） */
    public static final long DEBUG_ABOVE_PERMANENT = -8;
    /** debug4：wire = DEFAULT_TIME（150842304000000000，2079-01-01T00:00，客户端"过期"显示的实测下沿） */
    public static final long DEBUG_DEFAULT_TIME = -9;

    private boolean alive = true;

    private boolean summoned = false;
    private Point pos = new Point(0, 0);
    private int stance = 0;

    private int flags = 0;

    // NOTE: the list is assumed immutable
    private List<Integer> ignoreItems = new ArrayList<>();

    public enum PetFlag {
        OWNER_SPEED(0x01);

        private final int value;

        PetFlag(int i) {
            this.value = i;
        }

        public int getValue() {
            return value;
        }
    }

    /** 物种变更（孵化/任务进化）：宠物本体认知的物种随新宿主形态刷新并立即落库 */
    public void evolveTo(int newItemId) {
        itemId = newItemId;
        saveToDb();
    }

    public List<Integer> getIgnoreItems() {
        return ignoreItems;
    }

    /** 整体替换拾取屏蔽清单并立即落库（客户端提交过滤设置的落点） */
    public void setIgnoreItems(List<Integer> itemIds) {
        ignoreItems = itemIds;
        saveToDb();
    }

    // -- Object life cycle --

    public static Pet create(int itemId) {
        // wz info/life 单位为天，工厂边界统一换算为毫秒
        return Pet.create(itemId, TimeUnit.DAYS.toMillis(PetDataFactory.getLife(itemId)));
    }

    /** 建宠物对象：durationMs = 持续时长毫秒（<=0 = 永久） */
    public static Pet create(int itemId, long durationMs) {
        Pet pet = new Pet();
        pet.petId = CashIdGenerator.generateCashId();
        pet.itemId = itemId;
        if (durationMs > 0) {
            pet.expiration = Server.getInstance().getCurrentTime() + durationMs;
        } else if (durationMs == DEBUG_EXPIRE_PAST || durationMs == DEBUG_EXPIRE_FUTURE) {
            // TODO(debug, 临时): 相对探测日期（过去/未来 30 天）
            long offset = java.util.concurrent.TimeUnit.DAYS.toMillis(30);
            pet.expiration = Server.getInstance().getCurrentTime() + (durationMs == DEBUG_EXPIRE_PAST ? -offset : offset);
        } else if (durationMs == DEBUG_ABOVE_PERMANENT || durationMs == DEBUG_DEFAULT_TIME) {
            pet.expiration = durationMs;   // 哨兵直通：getTime/toWire 映射 PERMANENT+12h / DEFAULT_TIME
        }

        // NOTE: must save before return
        // if saving fails, creation fails
        pet.saveToDb();

        pet.finishInit();
        return pet;
    }

    public static Pet load(int petId) {
        Pet pet = loadedPets.get(petId);
        if (pet != null) {
            return pet;
        }

        pet = new Pet();

        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT data FROM pets_json WHERE petid = ?")) {
            ps.setInt(1, petId);

            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                pet.applyData(PetData.deserialize(rs.getString("data")));
            }
        } catch (SQLException e) {
            e.printStackTrace();
            throw new RuntimeException("Failed to load pet.");
        }

        pet.finishInit();
        return pet;
    }

    private Pet() {}

    private void finishInit() {
        loadedPets.put(petId, this);
        if (alive && expiration > 0) {
            expireTimer.schedule(petId, expiration);
        }
    }

    private void onExpire() {
        if (!alive) {
            log.warn("onExpire called on expired pet (petId:{})", petId);
            return;
        }

        if (PetDataFactory.canRevive(itemId)) {
            log.info("Pet expired (petId:{})", petId);
            // 先落库失活再通知：推送的宠物物品体经 LEGACY/translator 取 alive，映射"过期"哨兵
            alive = false;
            saveToDb();
            if (owner != null) {
                owner.onPetExpire(this);
            }

        } else {
            log.info("Pet permanently expired (petId:{}, itemId:{})", petId, itemId);
            if (owner != null) {
                owner.onPetDestroy(this);
            }
            deleteFromDb();
            dispose();
        }
    }

    /** Free unneeded instance to prevent resource leak. No semantic change to the game. */
    public void dispose() {
        expireTimer.cancel(petId);
        loadedPets.remove(petId);
        owner = null;
    }

    // -- Basic info --

    public void bind(CharacterPets newOwner) {
        if (owner != null) {
            owner.unregisterPet(this);
        }
        owner = newOwner;
        newOwner.registerPet(this);
    }

    public void bind(Character owner) {
        bind(owner.getPets());
    }

    public void unbind() {
        owner.unregisterPet(this);
        this.owner = null;
    }

    public int getPetId() {
        return petId;
    }

    public int getItemId() {
        return itemId;
    }

    public String getName() {
        return name == null ? ii.getName(itemId) : name;
    }

    public void setName(String name) {
        this.name = name;
    }

    // -- Feed --

    public int getTameness() {
        return tameness;
    }

    public int getLevel() {
        return level;
    }

    public int getFullness() {
        return fullness;
    }

    // todo: [refactor] handle inside
    public void setFullness(int fullness) {
        this.fullness = fullness;
    }

    public boolean isAlive() {
        return alive;
    }

    // -- Map object --

    public boolean isSummoned() {
        return summoned;
    }

    public void setSummoned(boolean yes) {
        this.summoned = yes;
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

    public void applyMovements(List<LifeMovementFragment> movement) {
        for (LifeMovementFragment move : movement) {
            if (move instanceof LifeMovement) {
                if (move instanceof AbsoluteLifeMovement) {
                    this.setPos(move.getPosition());
                }
                this.setStance(((LifeMovement) move).getNewstate());
            }
        }
    }

    // -- Persistence --

    public void saveToDb() {
        try (Connection con = DatabaseConnection.getConnection()) {
            saveToDb(con);
        } catch (SQLException e) {
            e.printStackTrace();
            throw new RuntimeException("Failed to save pet");
        }
    }

    /** 用指定连接保存：角色保存主事务内调用，消除第二写者（SQLite 单写者下避免 SQLITE_BUSY） */
    public void saveToDb(Connection con) {
        try (PreparedStatement ps = con.prepareStatement("INSERT INTO pets_json (petid, data) VALUES (?, ?) ON CONFLICT(petid) DO UPDATE SET data = excluded.data")) {
            ps.setInt(1, petId);
            ps.setString(2, toData().serialize());
            ps.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
            throw new RuntimeException("Failed to save pet");
        }
    }

    public void deleteFromDb() {
        try (Connection con = DatabaseConnection.getConnection()) {
            PreparedStatement ps = con.prepareStatement("DELETE FROM pets_json where petid=?");
            ps.setInt(1, petId);
            ps.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
            throw new RuntimeException("Failed to delete pet");
        }
    }

    PetData toData() {
        PetData data = new PetData();
        data.petId = petId;
        data.itemId = itemId;
        data.name = name;
        data.level = level;
        data.tameness = tameness;
        data.fullness = fullness;
        data.summoned = summoned;
        data.flags = flags;
        data.expiration = expiration;
        data.alive = alive;
        if (!ignoreItems.isEmpty()) {
            data.ignoreItems = ignoreItems;
        }
        return data;
    }

    void applyData(PetData data) {
        petId = data.petId;
        itemId = data.itemId;
        name = data.name;
        level = data.level;
        tameness = data.tameness;
        fullness = data.fullness;
        summoned = data.summoned;
        flags = data.flags;
        expiration = data.expiration;
        alive = data.alive;
        if (data.ignoreItems != null) {
            ignoreItems = data.ignoreItems;
        }
    }

    // -- TODO --

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
                    owner.getRemote().pet().petLevelUp(owner, slot);
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

        owner.getRemote().pet().petFoodResponse(owner, slot, enjoyed, owner.hasPetChatballoon(slot));
        saveToDb();

        ItemSlot petz = owner.findPetItemSlot(petId);
        if (petz != null) {
            owner.forceUpdateItem(petz);
        }
    }


    public Pair<Integer, Boolean> canConsume(int itemId) {
        return ii.canPetConsume(this.itemId, itemId);
    }



    // -- Flags --
    // FIXME: The whole usage is weird.

    public int getFlags() {
        return flags;
    }

    public void setFlag(PetFlag flag) {
        this.flags |= flag.getValue();
        saveToDb();

        if (owner != null) {
            ItemSlot petz = owner.getPlayer().findPetItemSlot(petId);
            if (petz != null) {
                owner.getPlayer().forceUpdateItem(petz);
            }
        }
    }
}
