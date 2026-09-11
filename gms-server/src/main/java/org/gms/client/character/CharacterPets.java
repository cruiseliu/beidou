package org.gms.client.character;

import org.gms.client.pet.Pet;
import org.gms.client.inventory.InventoryType;
import org.gms.client.inventory.Item;
import org.gms.client.inventory.ItemPool;
import org.gms.client.inventory.ItemSlot;
import org.gms.client.inventory.manipulator.InventoryManipulator;
import org.gms.constants.inventory.ItemConstants;
import org.gms.infra.KeyedTimers;
import org.gms.infra.Strand;
import org.gms.model.json.CharacterPetsData;
import org.gms.remote.ClientEventHandlerRegistry;
import org.gms.model.json.PetData;
import org.gms.remote.modules.pet.PetModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 宠物模块组件：三个宠物槽位 + 过滤配置（petignores 内存态）+ 持久化。
 *
 * <p><b>并发模型（M1 宠物 POC，角色操作队列化）</b>：本组件与名下 {@link Pet} 的状态变更
 * 全部收敛在 owner 的 strand（{@link Strand}，每连接一条串行队列）上执行——
 * <ul>
 *   <li><b>变更入口自封存</b>：grantPet/setIgnoreList/applyData 及 Pet 侧转移方法
 *       （bind/summon/dismiss/...）经 {@code onStrand} 提交，strand 内调用 inline、
 *       跨线程调用入队等待，调用方无需关心线程；</li>
 *   <li><b>读入口断言</b>：getSummonedPets/toData 等集合读经 {@code checkOnStrand}
 *       断言 strand 归属（迁移期 warn），暴露漏改道的调用路径；Pet 的单字段 getter
 *       不设防（最坏读到旧值，对发包无害）；</li>
 *   <li><b>已知例外</b>：dispose 在登出 5 分钟后（TimerManager 线程）执行——届时 strand
 *       已关闭、对象图静止，直接操作安全；savePetsToDb 的快照采集在 strand 上、
 *       落库在保存线程事务内（严禁持 DB 事务等 strand，BUSY/死锁配方）。</li>
 * </ul>
 * 跨角色读（其他玩家视角）phase 2 经 host 快照收口后，本模型即角色逻辑无锁化。
 */
public class CharacterPets implements PetModule.Handler {

    private static final Logger log = LoggerFactory.getLogger(CharacterPets.class);

    /** 召唤槽位上限 */
    private static final int MAX_SUMMONED = 3;

    private final Character owner;

    private final Map<Integer, Pet> allPets = new LinkedHashMap<>();  // petId -> pet
    private final List<Pet> summonedPets = new ArrayList<>();

    /** 拾取过滤共享列表（全角色宠物共用；按客户端提交序，随角色持久化） */
    private List<Integer> ignoreList = new ArrayList<>();  // NOTE: keep it unmodifiable

    /** 宠物饥饿/过期定时（key = petId），经 Supplier 跟随 owner 当前 strand；dispose 时关闭 */
    private volatile KeyedTimers hungerTimers;
    private volatile KeyedTimers expireTimers;

    CharacterPets(Character owner) {
        this.owner = owner;
        // 不在构造期自注册：构造上下文无 actor 可达（autosave/charlist 装载，doc/12）
    }

    /** 收包 Handler 接插（角色入场绑定时由 Character 聚合调用，on strand，doc/12） */
    void bindClientHandlers(ClientEventHandlerRegistry registry) {
        registry.registerPet(this);
    }

    /** Free unused resources. 登出 5 分钟后的延迟清理（届时 strand 已关闭、对象图静止，直接操作安全） */
    void dispose() {
        closePetTimers();
        for (Pet pet : allPets.values()) {
            pet.dispose();
        }
    }

    // -- Strand confinement --

    /**
     * 在 owner strand 上执行任务（已在其上则 inline）；无连接（mock/早期加载）时原地执行。
     * 跨包：Pet 侧转移方法经此自封存。
     */
    public void onStrand(String name, Runnable task) {
        onStrandResult(name, () -> {
            task.run();
            return null;
        });
    }

    /** 同 {@link #onStrand}，带返回值 */
    public <T> T onStrandResult(String name, Supplier<T> task) {
        Strand s = owner.strand();
        if (s != null) {
            return s.supply(name, task);
        }
        return task.get();
    }

    /** 读入口的 strand 归属断言（迁移期 warn，不中断）；strand 已关闭 → 静默（无并发写者，读安全） */
    private void checkOnStrand(String what) {
        Strand s = owner.strand();
        if (s != null && !s.isClosed()) {
            s.checkOnStrand(what);
        }
    }

    // -- In（收包入口：PetModule.Handler）--

    /**
     * SPAWN_PET：召唤/下阵/孵化（原 SpawnPetProcessor 的 gameplay 半边）。
     * 在 player strand 上执行（in 管线回调），读自己的背包与宠物状态。
     * unlock 回包由收包管线统一负责（按 event 类型判定），本方法不写。
     */
    @Override
    public void summonPet(int slot, boolean lead) {
        Character chr = owner;
        Pet pet = chr.getPetById(chr.getInventory(InventoryType.CASH).getItem(slot).getPetId());
        if (pet == null) {
            return;
        }
        if (pet.isEgg()) {
            pet.evolve();
            return;
        }
        if (!pet.isAlive()) {
            return;   // 失活宠物不可召唤
        }
        if (chr.getPetIndex(pet) != -1) {
            pet.dismiss();
            return;
        }
        if (chr.getSkillLevel(8) == 0 && chr.getPet(0) != null) {
            chr.getPet(0).dismiss();
        }
        java.awt.Point pos = chr.getPosition();
        pos.y -= 12;
        int fh = chr.getMap().getFootholds().findBelow(pet.getPos()).getId();
        pet.summon(!lead, pos, fh);
        chr.getRemote().pet().updateIgnoreList(chr);
    }

    // -- Ownership --

    /**
     * Grant a pet into the inventory.
     * The life duration is determined by the wz file.
     */
    public boolean grantPet(int itemId) {
        return onStrandResult("pets-grantPet", () -> grantPetInternal(itemId, null));
    }

    /**
     * Grant a pet and set its life duration.
     * If durationMs = -1, the pet will be permanent.
     */
    public boolean grantPet(int itemId, long durationMs) {
        return onStrandResult("pets-grantPet", () -> grantPetInternal(itemId, durationMs));
    }

    private boolean grantPetInternal(int itemId, Long duration) {
        // test first to avoid unnecessary db operations
        Item fakeItem = Item.fromPet(itemId, -1);
        // todo: [refactor] add a canHold() method?
        boolean check = owner.getInventory().testUpdate().add(fakeItem, 1).commit();
        if (!check) {
            return false;
        }

        Pet pet = (duration == null) ? Pet.create(itemId) : Pet.create(itemId, duration);
        Item item = Item.fromPet(itemId, pet.getPetId());
        boolean success = owner.getInventory().tryUpdate().add(item, 1).commit();
        // bind() is invoked by enter inventory hook（item script onEnterInventory，经脚本调度异步派发）
        if (!success) {
            // since we did not lock the inventory this might fail (rarely)
            pet.destroy();
        }
        return success;
    }

    Pet getPetById(int petId) {
        checkOnStrand("CharacterPets.getPetById");
        return allPets.get(petId);
    }

    /** Should only be called from Pet.bind()（已在 toOwner strand 上） */
    public void registerPet(Pet pet) {
        allPets.put(pet.getPetId(), pet);
    }

    /** Should only be called from Pet.unbind() or similar. */
    public void unregisterPet(Pet pet) {
        allPets.remove(pet.getPetId());
    }

    /** Used by scripts（脚本调度线程调用；bind 内部自封存到 strand） */
    public void handlePetEnterInventory(int petId) {
        Pet.load(petId).bind(this);
    }

    /** Used by scrpits. */
    public void handlePetLeaveInventory(int petId) {
        Pet.load(petId).unbind(this);
    }

    // -- Summoned --

    public List<Pet> getSummonedPets() {
        checkOnStrand("CharacterPets.getSummonedPets");
        return List.copyOf(summonedPets);
    }

    public Pet getSummonedPet(int index) {
        checkOnStrand("CharacterPets.getSummonedPet");
        return index < summonedPets.size() ? summonedPets.get(index) : null;
    }

    public boolean hasSummonedPet() {
        checkOnStrand("CharacterPets.hasSummonedPet");
        return summonedPets.size() > 0;
    }

    public int getSummonedPetIndex(Pet pet) {
        return getSummonedPetIndex(pet.getPetId());
    }

    int getSummonedPetIndex(int petId) {
        checkOnStrand("CharacterPets.getSummonedPetIndex");
        for (int i = 0; i < summonedPets.size(); i++) {
            if (summonedPets.get(i).getPetId() == petId) {
                return i;
            }
        }
        return -1;
    }

    /** Should only be called from Pet.summon()（已在 owner strand 上） */
    public void addSummonedPet(Pet pet, boolean atTail) {
        if (summonedPets.size() >= MAX_SUMMONED) {
            log.error("Summon {} when slots are full", pet);
            return;
        }
        if (atTail) {
            summonedPets.add(pet);
        } else {
            summonedPets.add(0, pet);
        }
    }

    /** Should only be called from Pet.dismiss()（已在 owner strand 上） */
    public void removeDismissedPet(Pet pet) {
        summonedPets.removeIf(p -> p.getPetId() == pet.getPetId());
    }

    // -- Companion item managerment --

    /** Should only be called from Pet.destroy() */
    public void removePetItem(Pet pet) {
        ItemSlot host = owner.findPetItemSlot(pet.getPetId());
        if (host != null) {
            // fixme: [refactor] use new inventory api
            InventoryManipulator.removeFromSlot(owner.getClient(), InventoryType.CASH, (short) host.getPosition(), (short) 1, false);
        }
    }

    /** Should only be called from Pet.evolve() */
    public int evolvePetItem(Pet pet, ItemPool resultPool) {
        Item oldItem = findPetItem(pet);
        boolean success = owner.getInventory().tryUpdate()
                .remove(oldItem, 1)
                .addPoolAndCommit(resultPool);
        if (!success) {
            log.error("No space to evolve {}", pet);
            return -1;
        }
        return findPetItem(pet).getId();
    }

    /** Should only be called from Pet.evolve() */
    public void evolvePetItem(Pet pet, int resultItemId) {
        Item oldItem = findPetItem(pet);
        Item newItem = Item.fromPet(resultItemId, pet.getPetId());
        boolean success = owner.getInventory().tryUpdate()
                .remove(oldItem, 1)
                .add(newItem, 1)
                .commit();
        if (!success) {
            log.error("No space to evolve {}", pet);
        }
    }

    private Item findPetItem(Pet pet) {
        // fixme: [refactor] use new inventory api
        for (ItemSlot slot : owner.getInventory(InventoryType.CASH).list()) {
            if (slot.getItem().getPetId() == pet.getPetId()) {
                return slot.getItem();
            }
        }
        log.error("Cannot find the item of {} in {}", pet, this);
        return null;
    }

    // -- ignore list --

    public List<Integer> getIgnoreList() {
        checkOnStrand("CharacterPets.getIgnoreList");
        return List.copyOf(ignoreList);
    }

    public void setIgnoreList(List<Integer> items) {
        onStrand("pets-setIgnoreList", () -> ignoreList = List.copyOf(items));
    }

    // -- Persistence --

    /** NOTE: Remember to call savePetsToDb() */
    public CharacterPetsData toData() {
        checkOnStrand("CharacterPets.toData");
        CharacterPetsData data = new CharacterPetsData();
        data.summoned = new ArrayList<>();
        for (Pet summonedPet : summonedPets) {
            data.summoned.add(summonedPet.getPetId());
        }
        data.ignoreItems = List.copyOf(ignoreList);
        return data;
    }

    public void applyData(CharacterPetsData data) {
        // 加载路径可能在事件循环上（charlist 预加载）；bind/summon 涉及定时器登记与 wire，须在 strand 上
        onStrand("pets-applyData", () -> applyDataInternal(data));
    }

    private void applyDataInternal(CharacterPetsData data) {
        ignoreList = new ArrayList<>(data.ignoreItems);
        for (int petId : data.summoned) {
            Pet pet;
            try {
                pet = Pet.load(petId);
            } catch (RuntimeException e) {
                // desync 容忍（最终一致性模型）：信封引用的宠物行缺失（kill 竞态等）——
                // 跳过召唤恢复、保留道具，不拒绝登录
                log.error("召唤恢复跳过：宠物 {} 行缺失（desync）", petId, e);
                continue;
            }
            pet.bind(this);
            if (pet.isAlive()) {
                // 有限期宠物补登记到期（bind 对同 owner no-op，登录/转换重载须重挂定时器；
                // scheduleOrRun = 登出期间已过期则 inline 判死）
                if (pet.getExpiration() > 0) {
                    expireTimers().scheduleOrRun(petId, pet.getExpiration());
                }
                pet.summonSilently(true);
            }
        }
    }

    /**
     * 保存快照束：信封 pets 段（summoned/ignoreItems）+ 全部宠物行快照，同一 strand 瞬时点原子采集
     * ——两者是"引用表与被引用对象"关系，撕裂会导致加载期引用缺失。
     * 采集等 strand（纯内存读、不持任何监视器/事务）；落库由调用方在保存线程事务内进行
     * （严禁持 DB 事务等 strand——strand 任务反撞本事务锁 = BUSY/死锁配方）。
     */
    public record SaveBundle(CharacterPetsData petsData, List<PetData> snapshots) {
    }

    public SaveBundle collectSaveBundle() {
        Strand s = owner.strand();
        if (s != null && !s.isClosed()) {
            return s.supply("pets-save-bundle", this::collectSaveBundleInternal);
        }
        // strand 已关闭（登出排空后）：无并发写者，直接读
        return collectSaveBundleInternal();
    }

    private SaveBundle collectSaveBundleInternal() {
        List<PetData> snapshots = new ArrayList<>();
        for (Pet pet : allPets.values()) {
            snapshots.add(pet.snapshot());
        }
        return new SaveBundle(toData(), snapshots);
    }

    /** 落库既有快照；调用方在保存线程事务内调用 */
    void savePetsToDb(Connection con, List<PetData> snapshots) {
        for (PetData data : snapshots) {
            Pet.saveToDb(con, data);
        }
    }

    // -- Pet timers --

    /**
     * 饥饿定时容器（key = petId）：召唤期间存活，dismiss 即取消。
     * 惰性创建，全部操作发生在 owner strand 上（无竞态创建问题）。
     */
    public KeyedTimers hungerTimers() {
        KeyedTimers t = hungerTimers;
        if (t == null) {
            t = newPetTimers((petId, scheduledMs) -> {
                Pet pet = allPets.get(petId);
                if (pet != null) {
                    pet.onHunger(scheduledMs);
                }
            });
            hungerTimers = t;
        }
        return t;
    }

    /** 到期定时容器（key = petId）：bind/revive 登记；acquire 时 scheduleOrRun 补判离线过期 */
    public KeyedTimers expireTimers() {
        KeyedTimers t = expireTimers;
        if (t == null) {
            t = newPetTimers((petId, scheduledMs) -> {
                Pet pet = allPets.get(petId);
                if (pet != null) {
                    pet.onExpire();
                }
            });
            expireTimers = t;
        }
        return t;
    }

    private KeyedTimers newPetTimers(KeyedTimers.TimerListener listener) {
        // 不向 strand 登记 close finalizer：strand 随连接走，Character 跨转换（商城/换频道）存活，
        // 误关后定时器永久失灵；登出后的迟到 post 由 DeadlineTimer 按 strand-closed 静默丢弃，
        // 残留取消由 5 分钟后的 dispose 收尾。
        return new KeyedTimers(() -> owner.strand(), listener);
    }

    /** 取消某只宠物的全部待触发定时（dispose/destroy/unbind 路径；容器未建则无需取消）。跨包：Pet 回调 */
    public void cancelPetTimers(int petId) {
        if (hungerTimers != null) {
            hungerTimers.cancel(petId);
        }
        if (expireTimers != null) {
            expireTimers.cancel(petId);
        }
    }

    /** 关闭两个定时容器（登出清理调用；此后 schedule 静默丢弃） */
    public void closePetTimers() {
        if (hungerTimers != null) {
            hungerTimers.close();
        }
        if (expireTimers != null) {
            expireTimers.close();
        }
    }

    // -- Character access --

    public Character getCharacter() {
        return owner;
    }

    public PetModule getRemote() {
        return owner.getRemote().pet();
    }

    // -- todo ---

    int getPetEquipItemId(byte petIndex) {
        if (!ItemConstants.isValidPetIndex(petIndex)) {
            return 0;
        }
        ItemSlot petEqp = owner.getInventory(InventoryType.EQUIPPED).getItem(ItemConstants.PET_EQUIP_SLOTS.get(petIndex).equip());
        return petEqp == null ? 0 : petEqp.getItemId();
    }

    boolean hasPetNameTag(int petIndex) {
        if (!ItemConstants.isValidPetIndex(petIndex)) {
            return false;
        }
        return owner.getInventory(InventoryType.EQUIPPED).getItem(ItemConstants.PET_EQUIP_SLOTS.get(petIndex).nameTag()) != null;
    }

    public boolean hasPetChatballoon(int petIndex) {
        if (!ItemConstants.isValidPetIndex(petIndex)) {
            return false;
        }
        return owner.getInventory(InventoryType.EQUIPPED).getItem(ItemConstants.PET_EQUIP_SLOTS.get(petIndex).chatBalloon()) != null;
    }

    boolean isEquippedMesoMagnet(byte petIndex) {
        if (!ItemConstants.isValidPetIndex(petIndex)) {
            return false;
        }
        return owner.getInventory(InventoryType.EQUIPPED).getItem(ItemConstants.PET_EQUIP_SLOTS.get(petIndex).mesoMagnet()) != null;
    }

    boolean isEquippedItemPouch(byte petIndex) {
        if (!ItemConstants.isValidPetIndex(petIndex)) {
            return false;
        }
        return owner.getInventory(InventoryType.EQUIPPED).getItem(ItemConstants.PET_EQUIP_SLOTS.get(petIndex).itemPouch()) != null;
    }

    boolean isEquippedPetItemIgnore(byte petIndex) {
        if (!ItemConstants.isValidPetIndex(petIndex)) {
            return false;
        }
        return owner.getInventory(InventoryType.EQUIPPED).getItem(ItemConstants.PET_EQUIP_SLOTS.get(petIndex).itemIgnore()) != null;
    }

    /** 旧三槽视图（含尾部 null）：legacy 出包消费方依赖定长布局 */
    Pet[] LEGACY_getActivePets() {
        checkOnStrand("CharacterPets.LEGACY_getActivePets");
        Pet[] slots = new Pet[MAX_SUMMONED];
        for (int i = 0; i < summonedPets.size(); i++) {
            slots[i] = summonedPets.get(i);
        }
        return slots;
    }

    // -- Debug --

    @Override
    public String toString() {
        String ret = "CharacterPets(id=" + owner.id + ", summoned=[";
        for (Pet pet : summonedPets) {
            ret += pet.getPetId() + ",";
        }
        ret += "])";
        return ret;
    }
}
