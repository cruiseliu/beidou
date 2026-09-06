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

/**
 * 宠物：与物品解耦的独立对象，自行管理生命周期。
 * 与宿主物品只靠 petid 关联、互不存引用：
 * - 宠物需要宿主物品时经 owner 遍历 CASH 背包按 petid 匹配（findPetItemSlot）；
 * - 物品需要宠物时经 CharacterPets 的 petid→Pet 映射查询。
 * itemId 是 wz 数据 key（宠物命令/饥饿/可食饲料判定），自持于此。
 * 纪律：部分方法跨 Pet 和 CharacterPets 双类，入口/主体统一放在此类，后者只放强依赖 Character 对象的部分。
 *
 * <p><b>并发模型（M1 宠物 POC）</b>：状态变更（bind/unbind/summon/dismiss/destroy/revive/
 * 亲密度/饱食度/命名/旗帜/进化）经 owner（或 bind 的目标 owner）strand 自封存——strand 内
 * 调用 inline、跨线程调用入队等待，调用方线程无关；全局注册表归 {@link PetManager} 壳；
 * 定时器归 owner 的 KeyedTimers（无主宠物零定时器，离线过期在 acquire 时经
 * scheduleOrRun 补判）。单字段 getter 不设防（最坏读到旧值，对发包无害）——
 * 严禁在 getter 上加锁/断言：remote 冻结路径持 remote monitor 调它们，加锁会制造
 * remote↔pet 反向锁序。
 */
public class Pet {
    private static final Logger log = LoggerFactory.getLogger(Pet.class);

    public static final long PERMANENT = -1;

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

        PetManager.get().register(pet);
        return pet;
    }

    public static Pet load(int petId) {
        return PetManager.get().getOrLoad(petId);
    }

    private Pet() {}

    Pet(int petId) {
        this.petId = petId;
    }

    /** Free unneeded instance to prevent resource leak. No semantic change to the game. */
    public void dispose() {
        if (owner != null) {
            owner.cancelPetTimers(petId);
        }
        PetManager.get().unregister(petId);
        owner = null;
    }

    // -- Ownership --

    public void bind(CharacterPets toOwner) {
        toOwner.onStrand("Pet.bind", () -> bindInternal(toOwner));
    }

    private void bindInternal(CharacterPets toOwner) {
        if (owner != toOwner) {
            if (owner != null) {
                log.warn("bind called on {} from {}", this, toOwner);
                owner.unregisterPet(this);
            }

            owner = toOwner;
            owner.registerPet(this);

            if (alive && expiration > 0) {
                // 获取时判过期（原 expiredOffline 语义）：deadline 已过 → inline 走 expireInternal
                owner.expireTimers().scheduleOrRun(petId, expiration);
            }
        }
    }

    public void unbind() {
        onStrand("Pet.unbind", () -> unbindInternal());
    }

    private void unbindInternal() {
        if (owner == null) {
            log.warn("unbind called on {}", this);
            return;
        }

        if (summoned) {
            dismiss();
        }
        owner.cancelPetTimers(petId);
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

    /** 到期定时回调（跨包：CharacterPets 的 expireTimers listener 专用入口） */
    public void onExpire() {
        if (!alive) {
            log.warn("onExpire called on expired {}", this);
            return;
        }
        expireInternal();
    }

    private void expireInternal() {
        if (PetWzHelper.canRevive(itemId)) {
            log.info("{} expired", this);
            alive = false;
            if (summoned) {
                if (getOwner().getMap() != null) {
                    dismiss();
                } else {
                    // 半加载态（角色加载期 acquire，map 未挂）：无 wire 可言，仅收敛召唤状态
                    owner.hungerTimers().cancel(petId);
                    summoned = false;
                    owner.removeDismissedPet(this);
                }
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
        onStrand("Pet.destroy", () -> destroyInternal());
    }

    private void destroyInternal() {
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
        onStrand("Pet.revive", () -> reviveInternal(durationMs));
    }

    private void reviveInternal(long durationMs) {
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
            owner.expireTimers().schedule(petId, expiration);
        }
    }

    // -- Summon --

    public void summon(boolean atTail, Point position, int foothold) {
        onStrand("Pet.summon", () -> {
            summonSilently(atTail);
            announceSummon(position, foothold);
        });
    }

    public void summonSilently(boolean atTail) {
        owner.hungerTimers().cancel(petId);

        summoned = true;

        long now = Server.getInstance().getCurrentTime();
        owner.hungerTimers().schedule(petId, now + PetWzHelper.getHungryInterval(itemId));

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
        onStrand("Pet.dismiss", () -> dismissInternal(false));
    }

    private void dismissInternal(boolean starve) {
        owner.hungerTimers().cancel(petId);

        summoned = false;

        owner.getRemote().dismissPet(this, starve);

        owner.removeDismissedPet(this);
        getOwner().getRemote().pet().updateIgnoreList(getOwner());

        // todo: [refactor] ugly api
        owner.getCharacter().enableActions();
    }

    // -- Interactions --

    public void addTameness(int delta) {
        onStrand("Pet.addTameness", () -> addTamenessInternal(delta));
    }

    private void addTamenessInternal(int delta) {
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
        onStrand("Pet.addFullness", () -> addFullnessInternal(delta));
    }

    private void addFullnessInternal(int delta) {
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
                owner.hungerTimers().schedule(petId, timestamp + PetWzHelper.getHungryInterval(itemId));
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
        onStrand("Pet.setName", () -> {
            this.name = name;
            saveToDb();  // major change, be conservative
        });
    }

    // -- Evolve --

    /**
     * Evolve the pet (or hatch the egg) and replace its item.
     * The result is determined by the wz file.
     * NOTE: This method does not consume the evolve stone or validate the level requirement.
     */
    public int evolve() {
        return onStrandResult("Pet.evolve", () -> {
            itemId = owner.evolvePetItem(this, getEvolveItemPool());
            saveToDb();
            return itemId;
        });
    }

    /** Evolve the pet to a fixed result and replace its item. */
    public void evolveTo(int newItemId) {
        onStrand("Pet.evolveTo", () -> {
            owner.evolvePetItem(this, newItemId);
            itemId = newItemId;
            saveToDb();
        });
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
        saveToDb(con, toData());
    }

    /** 状态快照（在 owner strand 上采集，随后任意线程写库）；序列化载荷 */
    public PetData snapshot() {
        return toData();
    }

    /** 用指定连接写入既有快照：快照在 strand 上采集（纯内存读），落库在保存线程的事务内，
     * 避免"持 DB 事务等 strand"的死锁/BUSY 配方（见 CharacterPets.savePetsToDb）。 */
    public static void saveToDb(Connection con, PetData snapshot) {
        try (PreparedStatement ps = con.prepareStatement("INSERT INTO pets_json (petid, data) VALUES (?, ?) ON CONFLICT(petid) DO UPDATE SET data = excluded.data")) {
            ps.setInt(1, snapshot.petId);
            ps.setString(2, snapshot.serialize());
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
        onStrand("Pet.setFlag", () -> {
            this.flags |= flag.getValue();
            saveToDb();
            if (owner != null) {
                owner.getRemote().updatePanel(this, false);
            }
        });
    }

    // -- Strand confinement --

    /** 在 owner 的 strand 上执行（已在其上则 inline）；无主时原地执行（后续操作自行暴露缺主问题） */
    private void onStrand(String name, Runnable task) {
        CharacterPets o = owner;
        if (o != null) {
            o.onStrand(name, task);
        } else {
            task.run();
        }
    }

    private <T> T onStrandResult(String name, java.util.function.Supplier<T> task) {
        CharacterPets o = owner;
        if (o != null) {
            return o.onStrandResult(name, task);
        }
        return task.get();
    }

    // -- Debug --

    @Override
    public String toString() {
        int ownerId = owner == null ? 0 : owner.getCharacter().getId();
        return "Pet(id=" + petId + ", itemId=" + itemId + ", ownerId=" + ownerId + ")";
    }
}
