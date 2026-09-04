package org.gms.client.pet;

import org.gms.client.character.Character;
import org.gms.client.character.CharacterPets;
import org.gms.client.inventory.Item;
import org.gms.client.inventory.ItemPool;
import org.gms.client.inventory.ItemStackWeight;
import org.gms.util.CashIdGenerator;
import org.gms.constants.game.ExpTable;
import org.gms.net.server.Server;
import org.gms.server.movement.AbsoluteLifeMovement;
import org.gms.server.movement.LifeMovement;
import org.gms.server.movement.LifeMovementFragment;
import org.gms.model.json.PetData;
import org.gms.util.DatabaseConnection;
import org.gms.util.I18nUtil;
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
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 宠物：与物品解耦的独立对象，自行管理生命周期。
 * 与宿主物品只靠 petid 关联、互不存引用：
 * - 宠物需要宿主物品时经 owner 遍历 CASH 背包按 petid 匹配（findPetItemSlot）；
 * - 物品需要宠物时经 CharacterPets 的 petid→Pet 映射查询。
 * itemId 是 wz 数据 key（宠物命令/饥饿/可食饲料判定），自持于此。
 * 纪律：部分方法跨 Pet 和 CharacterPets 双类，入口/主体统一放在此类，后者只放强依赖 Character 对象的部分。
 */
public class Pet {
    private static final Logger log = LoggerFactory.getLogger(Pet.class);

    public static final long PERMANENT = -1;

    private static final Map<Integer, Pet> loadedPets = new ConcurrentHashMap<>();

    private static final TimeoutHelper expireTimer = TimeoutHelper.createAndStart((petId, timestamp) -> {
        Pet pet = loadedPets.get(petId);
        if (pet != null) {
            pet.onExpire();
        }
    });

    private static final TimeoutHelper hungerTimer = TimeoutHelper.createAndStart((petId, timestamp) -> {
        Pet pet = loadedPets.get(petId);
        if (pet != null) {
            pet.onHunger(timestamp);
        }
    });

    // -- immutable identifier --

    private int petId;

    // -- local states --

    private int itemId;

    // null means the locale default name (todo: use client's locale)
    private String name = null;

    private int tameness = 0;
    private int level = 1;
    private int fullness = 100;

    private long expiration = PERMANENT;
    private boolean alive = true;
    private boolean expiredOffline = false;

    // -- owner and summoned states --

    private CharacterPets owner = null;

    private boolean summoned = false;
    private Point pos = new Point(0, 0);
    private int stance = 0;

    // -- to be refactor --

    private int flags = 0;

    // -- Object life cycle --

    public static Pet create(int itemId) {
        return Pet.create(itemId, PetWzHelper.getDurationMs(itemId));
    }

    public static Pet create(int itemId, long durationMs) {
        Pet pet = new Pet();
        pet.petId = CashIdGenerator.generateCashId();
        pet.itemId = itemId;
        if (durationMs > 0) {
            pet.expiration = Server.getInstance().getCurrentTime() + durationMs;
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
        pet.petId = petId;
        pet.loadFromDb();

        pet.finishInit();
        return pet;
    }

    private Pet() {}

    private void finishInit() {
        loadedPets.put(petId, this);
        if (alive && expiration > 0) {
            // at this time owner is not bound yet, so an immediate expire will set expiredOffline
            expireTimer.scheduleOrTrigger(petId, expiration);
        }
    }

    /** Free unneeded instance to prevent resource leak. No semantic change to the game. */
    public void dispose() {
        expireTimer.cancel(petId);
        hungerTimer.cancel(petId);
        loadedPets.remove(petId);
        owner = null;
    }

    // -- Ownership --

    public void bind(CharacterPets toOwner) {
        if (owner != toOwner) {
            if (owner != null) {
                log.warn("bind called on {} from {}", this, toOwner);
                owner.unregisterPet(this);
            }

            owner = toOwner;
            owner.registerPet(this);

            if (expiredOffline) {
                expireInternal();
                expiredOffline = false;
            }
        }
    }

    public void unbind() {
        if (owner == null) {
            log.warn("unbind called on {}", this);
            return;
        }

        if (summoned) {
            dismiss();
        }
        owner.unregisterPet(this);
        owner = null;
        saveToDb();
    }

    /** Identical to unbind(), but with a little more debug message. */
    public void unbind(CharacterPets fromOwner) {
        if (owner == fromOwner) {
            unbind();
        } else {
            log.error("unbind called on {} from {}", this, fromOwner);
        }
    }

    public Character getOwner() {
        return owner == null ? null : owner.getCharacter();
    }

    // -- Expire and revive --

    private void onExpire() {
        if (!alive) {
            log.warn("onExpire called on expired {}", this);
            return;
        }

        if (owner == null) {
            log.info("{} expired offline", this);
            alive = false;
            expiredOffline = true;
            saveToDb();
            dispose();

        } else {
            expireInternal();
        }
    }

    private void expireInternal() {
        if (PetWzHelper.canRevive(itemId)) {
            log.info("{} expired", this);
            alive = false;
            if (summoned) {
                dismiss();
            }
            saveToDb();
            owner.getRemote().expire(this);

        } else {
            destroy();
        }
    }

    /**
     * Permanently remove the pet.
     * This can only be called when the owner is online.
     */
    public void destroy() {
        log.info("{} destroyed", this);
        if (summoned) {
            dismiss();
        }
        owner.removePetItem(this);
        owner.unregisterPet(this);
        deleteFromDb();
        dispose();
    }

    // todo: [refactor] wire to this
    public void revive(long durationMs) {
        if (alive) {
            log.warn("revive called on live {}", this);
            return;
        }

        log.info("{} revived", this);
        alive = true;

        saveToDb();

        owner.getRemote().revive(this);

        if (durationMs > 0) {
            expiration = Server.getInstance().getCurrentTime() + durationMs;
            expireTimer.schedule(petId, expiration);
        }
    }

    // -- Summon --

    public void summon(boolean atTail, Point position, int foothold) {
        summonSilently(atTail);
        announceSummon(position, foothold);
    }

    public void summonSilently(boolean atTail) {
        hungerTimer.cancel(petId);

        summoned = true;

        long now = Server.getInstance().getCurrentTime();
        hungerTimer.schedule(petId, now + PetWzHelper.getHungryInterval(itemId));

        owner.addSummonedPet(this, atTail);
    }

    public void announceSummon(Point position, int foothold) {
        if (!summoned) {
            log.error("announceSummon called on dismissed {}", this);
            return;
        }

        pos = position;
        stance = 0;
        owner.getRemote().summonPet(this, foothold);
    }

    public void dismiss() {
        dismissInternal(false);
    }

    private void dismissInternal(boolean starve) {
        hungerTimer.cancel(petId);

        summoned = false;

        owner.getRemote().dismissPet(this, starve);

        owner.removeDismissedPet(this);
        getOwner().getRemote().pet().updateIgnoreList(getOwner());

        // todo: [refactor] ugly api
        owner.getCharacter().enableActions();
    }

    // -- Interactions --

    public void addTameness(int delta) {
        tameness = Math.max(0, tameness + delta);
        int oldLevel = level;
        recalcLevel();
        owner.getRemote().updatePanel(this, level > oldLevel);
    }

    private void recalcLevel() {
        int index = Arrays.binarySearch(ExpTable.getPetTamenessArray(), tameness);
        if (index < 0) {
            index = -index - 2;  // for (a[k] < x < a[k+1]) it returns -k-2
        }

        level = Math.clamp(index + 1, 1, 30);
    }

    public void addFullness(int delta) {
        fullness = Math.clamp(fullness + delta, 0, 100);
        owner.getRemote().updatePanel(this, false);
    }

    public void onHunger(long timestamp) {
        if (summoned) {
            addFullness(-1);
            if (fullness == 0) {
                addTameness(-1);
                dismissInternal(true);
                fullness = 5;
            } else {
                hungerTimer.schedule(petId, timestamp + PetWzHelper.getHungryInterval(itemId));
            }

            owner.getCharacter().dropMessage(6, I18nUtil.getMessage("Character.runFullnessSchedule"));
        }
    }

    public void announceFeedResult(boolean enjoy) {
        Character player = owner.getCharacter();
        int slot = owner.getSummonedPetIndex(this);
        owner.getRemote().petFoodResponse(player, slot, enjoy, owner.hasPetChatballoon(slot));
    }

    // TODO [refactor]
    // public void chat() {
    // }

    public void setName(String name) {
        this.name = name;
        saveToDb();  // major change, be conservative
    }

    // -- Evolve --

    /**
     * Evolve the pet (or hatch the egg) and replace its item.
     * The result is determined by the wz file.
     * NOTE: This method does not consume the evolve stone or validate the level requirement.
     */
    public int evolve() {
        itemId = owner.evolvePetItem(this, getEvolveItemPool());
        saveToDb();
        return itemId;
    }

    /** Evolve the pet to a fixed result and replace its item. */
    public void evolveTo(int newItemId) {
        owner.evolvePetItem(this, newItemId);
        itemId = newItemId;
        saveToDb();
    }

    public boolean isEgg() {
        return PetWzHelper.isEgg(itemId);
    }

    public ItemPool getEvolveItemPool() {
        if (!PetWzHelper.canEvolve(itemId)) {
            return null;
        }

        List<ItemStackWeight> pool = new ArrayList<>();
        for (Pair<Integer, Integer> candidate : PetWzHelper.getEvolvePool(itemId)) {
            int evolveItemId = candidate.getLeft();
            int prob = candidate.getRight();
            Item evolveResult = Item.fromPet(evolveItemId, petId);
            pool.add(new ItemStackWeight(evolveResult, 1, prob));
        }
        return new ItemPool(pool);
    }

    // -- Map states --
    // todo: [refactor] move to a MapObject?

    public void setPos(Point pos) {
        this.pos = pos;
    }

    public void applyMovements(List<LifeMovementFragment> movement) {
        for (LifeMovementFragment move : movement) {
            if (move instanceof LifeMovement) {
                if (move instanceof AbsoluteLifeMovement) {
                    pos = move.getPosition();
                }
                stance = ((LifeMovement) move).getNewstate();
            }
        }
    }

    // -- Persistence --

    public void loadFromDb() {
        String json;
        try (Connection con = DatabaseConnection.getConnection();
            PreparedStatement ps = con.prepareStatement("SELECT data FROM pets_json WHERE petid=?")) {
            ps.setInt(1, petId);
            ResultSet rs = ps.executeQuery();
            rs.next();
            json = rs.getString("data");
        } catch (SQLException e) {
            e.printStackTrace();
            throw new RuntimeException("Failed to load pet");
        }
        applyData(PetData.deserialize(json));
    }

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
        data.expiration = expiration;
        data.alive = alive;
        data.expiredOffline = expiredOffline;
        data.flags = flags;
        return data;
    }

    void applyData(PetData data) {
        petId = data.petId;
        itemId = data.itemId;
        name = data.name;
        level = data.level;
        tameness = data.tameness;
        fullness = data.fullness;
        expiration = data.expiration;
        alive = data.alive;
        expiredOffline = data.expiredOffline;
        flags = data.flags;
    }

    // -- Getters --

    public int getPetId() {
        return petId;
    }

    public int getItemId() {
        return itemId;
    }

    public String getName() {
        return name == null ? PetWzHelper.getItemName(itemId) : name;
    }

    public int getTameness() {
        return tameness;
    }

    public int getLevel() {
        return level;
    }

    public int getFullness() {
        return fullness;
    }

    /** 到期 epoch 毫秒（-1 = 永久）；协议组装侧经 Item.LEGACY_getExpiration 间接消费 */
    public long getExpiration() {
        return expiration;
    }

    public boolean isAlive() {
        return alive;
    }

    public Point getPos() {
        return pos;
    }

    public int getStance() {
        return stance;
    }

    // -- todo --

    // this should be unnecessary if it's cohensive enough
    public boolean isSummoned() {
        return summoned;
    }

    // FIXME: The whole mechanic is weird
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

    public int getFlags() {
        return flags;
    }

    public void setFlag(PetFlag flag) {
        this.flags |= flag.getValue();
        saveToDb();
        if (owner != null) {
            owner.getRemote().updatePanel(this, false);
        }
    }

    // -- Debug --

    @Override
    public String toString() {
        int ownerId = owner == null ? 0 : owner.getCharacter().getId();
        return "Pet(id=" + petId + ", itemId=" + itemId + ", ownerId=" + ownerId + ")";
    }
}
