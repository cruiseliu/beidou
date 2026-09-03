package org.gms.client.pet;

import org.gms.client.character.Character;
import org.gms.client.character.CharacterPets;
import org.gms.client.inventory.Item;
import org.gms.client.inventory.ItemPool;
import org.gms.client.inventory.ItemSlot;
import org.gms.client.inventory.ItemStackWeight;
import org.gms.util.CashIdGenerator;
import org.gms.constants.game.ExpTable;
import org.gms.net.server.Server;
import org.gms.server.ItemInformationProvider;
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
import java.util.concurrent.TimeUnit;

/**
 * 宠物：与物品解耦的独立对象，自行管理生命周期。
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

    private String name = null;

    private int tameness = 0;
    private int level = 1;
    private int fullness = 100;

    private long expiration = -1;
    private boolean alive = true;

    // NOTE: the list is assumed immutable
    private List<Integer> ignoreItems = new ArrayList<>();

    // -- owner states --

    private CharacterPets owner = null;

    private boolean summoned = false;
    private Point pos = new Point(0, 0);
    private int stance = 0;

    // -- to be refactor --

    private int flags = 0;

    // -- object life cycle --

    public static Pet create(int itemId) {
        // wz info/life 单位为天，工厂边界统一换算为毫秒
        return Pet.create(itemId, TimeUnit.DAYS.toMillis(PetDataFactory.getLife(itemId)));
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
            expireTimer.schedule(petId, expiration);
        }
    }

    /** Free unneeded instance to prevent resource leak. No semantic change to the game. */
    public void dispose() {
        expireTimer.cancel(petId);
        hungerTimer.cancel(petId);
        loadedPets.remove(petId);
        owner = null;
    }

    private void destroy() {
        log.info("{} destroyed", this);
        if (owner != null) {
            owner.handleDestroy(this);
        }
        deleteFromDb();
        dispose();
    }

    // -- expire and revive --

    private void onExpire() {
        if (!alive) {
            log.warn("onExpire called on expired {}", this);
            return;
        }

        if (!PetDataFactory.canRevive(itemId)) {
            destroy();
            return;
        }

        log.info("{} expired", this);
        alive = false;

        if (owner != null) {
            owner.handleExpire(this);
        }

        saveToDb();
    }

    // todo: [refactor] wire to this
    public void revive(long durationMs) {
        if (alive) {
            log.warn("revive called on live {}", this);
            return;
        }

        log.info("{} revived", this);
        alive = true;

        if (durationMs > 0) {
            expiration = Server.getInstance().getCurrentTime() + durationMs;
            expireTimer.schedule(petId, expiration);
        }

        saveToDb();
    }

    // -- player operations --

    public void bind(CharacterPets newOwner) {
        if (owner != newOwner) {
            if (owner != null) {
                log.warn("bind called on {} bound to character {}", this, owner.getCharacterId());
                owner.unregisterPet(this);
            }
            owner = newOwner;
            owner.registerPet(this);
        }
    }

    public void unbind() {
        if (summoned) {
            desummon();
        }
        if (owner != null) {
            owner.unregisterPet(this);
            owner = null;
            saveToDb();
        }
    }

    /** Identical to unbind(), but with a little more debug message. */
    public void unbind(CharacterPets oldOwner) {
        if (owner == oldOwner) {
            unbind();
        } else {
            log.error("unbind called on {} from bad owner {}", this, oldOwner.getCharacterId());
        }
    }

    public void summon(Point position, int foothold) {
        Character player = owner.getPlayer();

        hungerTimer.cancel(petId);
        pos = position;
        stance = 0;

        summoned = true;

        long now = Server.getInstance().getCurrentTime();
        hungerTimer.schedule(petId, now + PetDefinitionHelper.getHungryInterval(itemId));

        owner.addPet(this);

        player.getRemote().pet().summonPet(player, this, foothold);
    }

    public void desummon() {
        Character player = owner.getPlayer();

        hungerTimer.cancel(petId);

        summoned = false;

        player.getRemote().pet().desummonPet(player, this, false);

        owner.removePet(this, true);
        owner.commitExcludedItems();

        player.enableActions();
    }

    // -- management --

    public Character getOwner() {
        return owner == null ? null : owner.getPlayer();
    }

    public void setName(String name) {
        this.name = name;
        saveToDb();  // major change, be conservative
    }

    public void setIgnoreItems(List<Integer> itemIds) {
        ignoreItems = itemIds;
    }

    // -- evolve --

    public void evolve(int newItemId) {
        itemId = newItemId;
        saveToDb();  // major change, be conservative
        // fixme: [refactor] notify client?
    }

    public boolean isEgg() {
        return PetDefinitionHelper.isEgg(itemId);
    }

    public ItemPool getEvolvePool() {
        if (!PetDefinitionHelper.canEvolve(itemId)) {
            return null;
        }

        List<ItemStackWeight> pool = new ArrayList<>();
        for (Pair<Integer, Integer> candidate : PetDefinitionHelper.getEvolvePool(itemId)) {
            int evolveItemId = candidate.getLeft();
            int prob = candidate.getRight();
            Item evolveResult = Item.fromPet(evolveItemId, petId);
            pool.add(new ItemStackWeight(evolveResult, 1, prob));
        }
        return new ItemPool(pool);
    }

    // -- interactions --

    public void addTameness(int delta) {
        tameness = Math.max(0, tameness + delta);
        int oldLevel = level;
        recalcLevel();
        owner.getPlayer().getRemote().pet().updatePanel(this, level > oldLevel);
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
        owner.getPlayer().getRemote().pet().updatePanel(this, false);
    }

    public void onHunger(long timestamp) {
        if (summoned) {
            addFullness(-1);
            if (fullness == 0) {
                addTameness(-1);
                desummon();
                fullness = 5;
            } else {
                hungerTimer.schedule(petId, timestamp + PetDefinitionHelper.getHungryInterval(itemId));
            }

            owner.getPlayer().dropMessage(6, I18nUtil.getMessage("Character.runFullnessSchedule"));
        }
    }

    public void announceFeedResult(boolean enjoy) {
        Character player = owner.getPlayer();
        int slot = owner.getPetIndex(this);
        player.getRemote().pet().petFoodResponse(player, slot, enjoy, owner.hasPetChatballoon(slot));
    }

    // TODO
    // public void chat() {}

    // -- map object --
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

    // -- getters --

    public int getPetId() {
        return petId;
    }

    public int getItemId() {
        return itemId;
    }

    public String getName() {
        return name == null ? ii.getName(itemId) : name;
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

    public List<Integer> getIgnoreItems() {
        return ignoreItems;
    }

    public Point getPos() {
        return pos;
    }

    public int getStance() {
        return stance;
    }

    // -- Flags --
    // FIXME: The whole usage is weird.

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
            ItemSlot petz = owner.getPlayer().findPetItemSlot(petId);
            if (petz != null) {
                owner.getPlayer().forceUpdateItem(petz);
            }
        }
    }

    public boolean isSummoned() {
        return summoned;
    }

    public void setSummoned(boolean yes) {
        this.summoned = yes;
    }

    // -- Debug --
    @Override
    public String toString() {
        int ownerId = owner == null ? 0 : owner.getCharacterId();
        return "Pet(id=" + petId + ", itemId=" + itemId + ", ownerId=" + ownerId + ")";
    }
}
