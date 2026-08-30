package org.gms.client.character;

import org.gms.client.Client;
import org.gms.client.pet.Pet;
import org.gms.client.pet.PetDataFactory;
import org.gms.client.inventory.InventoryType;
import org.gms.client.inventory.ItemSlot;
import org.gms.client.inventory.manipulator.InventoryManipulator;
import org.gms.constants.id.ItemId;
import org.gms.constants.inventory.ItemConstants;
import org.gms.dao.entity.PetignoresDO;
import org.gms.manager.ServerManager;
import org.gms.net.server.Server;
import org.gms.server.TimerManager;
import org.gms.service.InventoryService;
import org.gms.util.CashIdGenerator;
import org.gms.util.I18nUtil;
import org.gms.util.Locks;

import java.awt.Point;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

/**
 * 宠物模块组件：三个宠物槽位 + 过滤配置（petignores 内存态）+ 拾取瞬移上下文 + 持久化。
 * 仿照 CharacterBuffs 模式：数据 + 领域逻辑内聚于此，持有 owner 反向引用，自有锁（lock）串行化，
 * Character 保留公开具名门面（getPets/unEquipPet/... 对外转发）。
 *
 * 锁说明：pets 数组、excluded/excludedItems、拾取上下文共用本类 lock——
 * 原实现 pets 用 petLock、excluded 用 chrLock、且 petLock 还被 lastVisitedMaps 误用，
 * 重构时统一收敛（lastVisitedMaps 已在 Character 中改用自己的锁）。
 */
public class CharacterPets {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(CharacterPets.class);
    private static final InventoryService inventoryService = ServerManager.getApplicationContext().getBean(InventoryService.class);
    private static final long PET_LOOT_TELEPORT_CONTEXT_EXPIRE_NS = MILLISECONDS.toNanos(1500L);

    private final Character owner;

    /** 三个召唤槽位（沿用旧名 pets） */
    private final Pet[] pets = new Pet[3];

    /** 全量宠物：petid → Pet（角色负责管理；与物品只靠 petid 关联，互不存引用） */
    private final Map<Integer, Pet> allPets = new LinkedHashMap<>();

    /** 宠物模块锁：串行化槽位/过滤配置/拾取上下文 */
    private final Lock lock = new ReentrantLock(true);

    /** 宠物ID → 屏蔽道具ID集（内存态，增量同步到 petignores 表） */
    private final Map<Integer, Set<Integer>> excluded = new LinkedHashMap<>();

    /** 本角色已生效的屏蔽道具ID集（客户端加载列表用） */
    private final Set<Integer> excludedItems = new LinkedHashSet<>();

    /** 宠物拾取补偿用传送前坐标（1.5s 有效） */
    private Point petLootTeleportBeforePos = null;
    private long petLootTeleportBeforePosTime = 0;

    /** 最近到期调度任务（单个；adopt/release/到期时重排） */
    private ScheduledFuture<?> expiryTask;

    CharacterPets(Character owner) {
        this.owner = owner;
    }

    // ── 全量管理 ──

    /** 宠物登记（创建/加载时调用） */
    void registerPet(Pet pet) {
        try (var ignored = Locks.acquire(lock)) {
            allPets.put(pet.getUniqueId(), pet);
        }
    }

    /** 按 petid 查询全量宠物（物品→宠物方向；未登记返回 null） */
    Pet getPetById(int petid) {
        try (var ignored = Locks.acquire(lock)) {
            return allPets.get(petid);
        }
    }

    /** 宠物注销（物品删除时调用；同步移出召唤槽） */
    void unregisterPet(int petid) {
        try (var ignored = Locks.acquire(lock)) {
            allPets.remove(petid);
            for (int i = 0; i < 3; i++) {
                if (pets[i] != null && pets[i].getUniqueId() == petid) {
                    pets[i] = null;
                }
            }
        }
    }

    /**
     * 驻留登记（JS hook onEnterInventory 的落点）：宿主物品进入本角色背包。
     * 幂等：已驻留 no-op。summoned=1 的宠物恢复召唤槽（≤3）并加载过滤配置。
     * 到期已过的宠物先补算到期策略（离线到期不依赖定时器）。
     */
    public void adoptPet(int petId, int itemId, boolean isLogin) {
        if (getPetById(petId) != null) {
            return;
        }
        Pet pet = Pet.loadFromDb(owner, itemId, petId);
        if (pet == null) {
            log.warn("宠物物品 petId={} 无对应 pets 行（脏数据），忽略登记 chr={}", petId, owner.getId());
            return;
        }
        registerPet(pet);
        if (isExpired(pet)) {
            applyExpirationPolicy(pet);
        } else if (pet.isSummoned()) {
            if (getNoPets() < 3) {
                addPet(pet);
                loadPetExcludedItems(petId);
                commitExcludedItems();
                // adopt 异步于登录流程：饥饿注册随召唤恢复进行（替代 PlayerLoggedinHandler 的槽位遍历）
                owner.getClient().getWorldServer().registerPetHunger(owner, owner.getPetIndex(pet));
            } else {
                pet.setSummoned(false);
                pet.saveToDb();
            }
        }
        rescheduleExpiry();
    }

    /**
     * 驻留注销（JS hook onLeaveInventory 的落点）：宿主物品离开本角色背包
     * （存现金仓库/移除等）。召唤中则先下阵。幂等：未驻留 no-op。
     */
    public void releasePet(int petId) {
        Pet pet = getPetById(petId);
        if (pet == null) {
            return;
        }
        if (getPetIndex(pet) > -1) {
            unEquipPet(pet, true);
        }
        unregisterPet(petId);
        rescheduleExpiry();
    }

    // ── 生命周期：授予与孵化（doc/11 §5）──

    /**
     * 发放宠物入本角色背包（脚本独立 API gainPet 的落点）：
     * 建 pets 行 → 物品带 petid 入包（enter 钩子 adopt 登记）→ 入包失败补偿删行。
     */
    public boolean grantPet(int itemId, long expiresAt) {
        int petId = Pet.createPetData(itemId, expiresAt);
        if (petId == -1) {
            return false;
        }
        if (!InventoryManipulator.REFACTOR6_addById(owner.getClient(), itemId, (short) 1, null, petId, -1)) {
            Pet.deletePetRow(petId);
            return false;
        }
        rescheduleExpiry();
        return true;
    }

    /**
     * 改种（孵化/任务进化）：进化龙/宝贝龙/绿龙是不同物品，先后引用同一 petId——
     * 同一 petId 换宿主物品（remove + add），pets 行不换。先试算空间，失败不动原物。
     */
    public boolean evolvePet(int petId, int newItemId) {
        Pet pet = getPetById(petId);
        if (pet == null) {
            return false;
        }
        ItemSlot host = owner.findPetItemSlot(petId);
        if (host == null) {
            return false;
        }
        var c = owner.getClient();
        if (!InventoryManipulator.checkSpace(c, newItemId, 1, "")) {
            return false;
        }
        InventoryManipulator.removeFromSlot(c, InventoryType.CASH, (short) host.getPosition(), (short) 1, false);
        if (!InventoryManipulator.REFACTOR6_addById(c, newItemId, (short) 1, null, petId, -1)) {
            // 理论上 checkSpace 后不会到这；兜底回插旧形态，避免宿主悬空
            InventoryManipulator.REFACTOR6_addById(c, pet.getItemId(), (short) 1, null, petId, -1);
            return false;
        }
        rescheduleExpiry();
        return true;
    }

    // ── 生命周期：到期（doc/11 §6）──

    /** 宠物到期判定：expires_at > 0 且已过（-1 = 永久） */
    private static boolean isExpired(Pet pet) {
        return pet.isActive() && pet.getExpiresAt() > 0 && pet.getExpiresAt() <= Server.getInstance().getCurrentTime();
    }

    /**
     * 到期策略：默认 = 失活（下阵 + active=0，宿主物品与 pets 行原样保留）；
     * 符文蜗牛例外 = 永久销毁（下阵 + 删行 + 移除宿主物品）。
     * FIXME [pet] 蜗牛官方语义为"未召唤时计时暂停"（expires_at 改召唤中累积），本期墙钟直算。
     */
    private void applyExpirationPolicy(Pet pet) {
        if (getPetIndex(pet) > -1) {
            unEquipPet(pet, true);
        }
        if (pet.getItemId() == ItemId.PET_SNAIL) {
            // 蜗牛销毁：leave 钩子回灌 releasePet 时对象已注销，幂等收敛
            Pet.deleteFromDb(owner, pet.getUniqueId());
            unregisterPet(pet.getUniqueId());
            ItemSlot host = owner.findPetItemSlot(pet.getUniqueId());
            if (host != null) {
                InventoryManipulator.removeFromSlot(owner.getClient(), InventoryType.CASH, (short) host.getPosition(), (short) 1, false);
            }
            log.info("试用宠物到期销毁 petId={} chr={}", pet.getUniqueId(), owner.getId());
        } else {
            pet.setActive(false);
            pet.saveToDb();
            log.info("宠物到期失活 petId={} chr={}", pet.getUniqueId(), owner.getId());
        }
    }

    /**
     * 到期调度：单个"本角色最近的将来到期"任务（adopt/release/到期时重排）。
     * 离线到期由 adopt 补算覆盖，无需跨会话定时器。
     */
    private void rescheduleExpiry() {
        if (expiryTask != null) {
            expiryTask.cancel(false);
            expiryTask = null;
        }
        long now = Server.getInstance().getCurrentTime();
        long nearest = Long.MAX_VALUE;
        synchronized (this) {
            for (Pet pet : allPets.values()) {
                if (pet.isActive() && pet.getExpiresAt() > now && pet.getExpiresAt() < nearest) {
                    nearest = pet.getExpiresAt();
                }
            }
        }
        if (nearest < Long.MAX_VALUE) {
            expiryTask = TimerManager.getInstance().register(this::runExpiryCheck, nearest - now + 500);
        }
    }

    private void runExpiryCheck() {
        boolean changed = false;
        for (Pet pet : List.copyOf(allPets.values())) {
            if (isExpired(pet)) {
                applyExpirationPolicy(pet);
                changed = true;
            }
        }
        if (changed) {
            rescheduleExpiry();
        }
    }

    // ── 槽位管理 ──
    void addPet(Pet pet) {
        try (var ignored = Locks.acquire(lock)) {
            for (int i = 0; i < 3; i++) {
                if (pets[i] == null) {
                    pets[i] = pet;
                    return;
                }
            }
        }
    }

    void removePet(Pet pet, boolean shift_left) {
        try (var ignored = Locks.acquire(lock)) {
            int slot = -1;
            for (int i = 0; i < 3; i++) {
                if (pets[i] != null) {
                    if (pets[i].getUniqueId() == pet.getUniqueId()) {
                        pets[i] = null;
                        slot = i;
                        break;
                    }
                }
            }
            if (shift_left) {
                if (slot > -1) {
                    for (int i = slot; i < 3; i++) {
                        if (i != 2) {
                            pets[i] = pets[i + 1];
                        } else {
                            pets[i] = null;
                        }
                    }
                }
            }
        }
    }

    void shiftPetsRight() {
        try (var ignored = Locks.acquire(lock)) {
            if (pets[2] == null) {
                pets[2] = pets[1];
                pets[1] = pets[0];
                pets[0] = null;
            }
        }
    }

    int getNoPets() {
        try (var ignored = Locks.acquire(lock)) {
            int ret = 0;
            for (int i = 0; i < 3; i++) {
                if (pets[i] != null) {
                    ret++;
                }
            }
            return ret;
        }
    }

    Pet[] getPets() {
        try (var ignored = Locks.acquire(lock)) {
            return Arrays.copyOf(pets, pets.length);
        }
    }

    Pet getPet(int index) {
        if (index < 0) {
            return null;
        }
        try (var ignored = Locks.acquire(lock)) {
            return pets[index];
        }
    }

    byte getPetIndex(int petId) {
        try (var ignored = Locks.acquire(lock)) {
            for (byte i = 0; i < 3; i++) {
                if (pets[i] != null) {
                    if (pets[i].getUniqueId() == petId) {
                        return i;
                    }
                }
            }
            return -1;
        }
    }

    byte getPetIndex(Pet pet) {
        try (var ignored = Locks.acquire(lock)) {
            for (byte i = 0; i < 3; i++) {
                if (pets[i] != null) {
                    if (pets[i].getUniqueId() == pet.getUniqueId()) {
                        return i;
                    }
                }
            }
            return -1;
        }
    }

    // ── 装备配件（读 EQUIPPED 背包的宠物装备槽） ──

    int getPetEquipItemId(byte petIndex) {
        if (!ItemConstants.isValidPetIndex(petIndex)) {
            return 0;
        }
        ItemSlot petEqp = owner.getInventory(InventoryType.EQUIPPED).getItem(ItemConstants.PET_EQUIP_SLOTS.get(petIndex).equip());
        return petEqp == null ? 0 : petEqp.getItemId();
    }

    boolean hasPetNameTag(byte petIndex) {
        if (!ItemConstants.isValidPetIndex(petIndex)) {
            return false;
        }
        return owner.getInventory(InventoryType.EQUIPPED).getItem(ItemConstants.PET_EQUIP_SLOTS.get(petIndex).nameTag()) != null;
    }

    boolean hasPetChatballoon(byte petIndex) {
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

    // ── 生命周期 ──

    void unEquipPet(Pet pet, boolean shift_left) {
        unEquipPet(pet, shift_left, false);
    }

    void unEquipPet(Pet pet, boolean shift_left, boolean hunger) {
        byte petIdx = getPetIndex(pet);
        Pet chrPet = getPet(petIdx);

        if (chrPet != null) {
            chrPet.setSummoned(false);
            chrPet.saveToDb();
        }

        owner.getClient().getWorldServer().unregisterPetHunger(owner, petIdx);
        owner.getRemote().pet().desummonPet(owner, chrPet, hunger);

        removePet(pet, shift_left);
        commitExcludedItems();

        owner.enableActions();
    }

    void runFullnessSchedule(int petSlot) {
        Pet pet = getPet(petSlot);
        if (pet == null) {
            return;
        }

        int newFullness = pet.getFullness() - PetDataFactory.getHunger(pet.getItemId());
        if (newFullness <= 5) {
            pet.setFullness(15);
            pet.saveToDb();
            unEquipPet(pet, true);
            owner.dropMessage(6, I18nUtil.getMessage("Character.runFullnessSchedule"));
        } else {
            pet.setFullness(newFullness);
            pet.saveToDb();
            ItemSlot petz = owner.findPetItemSlot(pet.getUniqueId());
            if (petz != null) {
                owner.forceUpdateItem(petz);
            }
        }
    }

    // ── 过滤配置（petignores 内存态） ──

    void resetExcluded(int petId) {
        try (var ignored = Locks.acquire(lock)) {
            Set<Integer> petExclude = excluded.get(petId);

            if (petExclude != null) {
                petExclude.clear();
            } else {
                excluded.put(petId, new LinkedHashSet<>());
            }
        }
    }

    void addExcluded(int petId, int x) {
        try (var ignored = Locks.acquire(lock)) {
            excluded.get(petId).add(x);
        }
    }

    /**
     * 统一从数据库加载单只宠物的过滤配置，确保召唤时内存状态与数据库保持一致。
     */
    void loadPetExcludedItems(int petId) {
        List<Integer> excludedItemIds = inventoryService.getPetIgnoreByPetId(petId).stream()
                .map(PetignoresDO::getItemid)
                .filter(Objects::nonNull)
                .toList();
        replacePetExcludedItemsInMemory(petId, excludedItemIds);
    }

    /** 客户端提交过滤设置时，直接按差异增量更新数据库，避免角色保存时再做危险的全量删写。 */
    void updatePetExcludedItems(int petId, Set<Integer> newExcludedItems) {
        Set<Integer> currentExcludedItems = getExcludedForPet(petId);
        Set<Integer> normalizedExcludedItems = new LinkedHashSet<>(newExcludedItems);

        Set<Integer> toAdd = new LinkedHashSet<>(normalizedExcludedItems);
        toAdd.removeAll(currentExcludedItems);

        Set<Integer> toRemove = new LinkedHashSet<>(currentExcludedItems);
        toRemove.removeAll(normalizedExcludedItems);

        inventoryService.addPetIgnoreItems(petId, toAdd);
        inventoryService.removePetIgnoreItems(petId, toRemove);
        replacePetExcludedItemsInMemory(petId, normalizedExcludedItems);
    }

    /** 宠物被永久删除时同步清理数据库和角色内存中的过滤配置，避免残留脏数据。 */
    void deletePetExcludedData(int petId) {
        inventoryService.deletePetData(petId);
        removeExcluded(petId);
    }

    Set<Integer> getExcludedForPet(int petId) {
        try (var ignored = Locks.acquire(lock)) {
            Set<Integer> petExcludedItems = excluded.get(petId);
            if (petExcludedItems == null) {
                return Collections.emptySet();
            }
            return Collections.unmodifiableSet(new LinkedHashSet<>(petExcludedItems));
        }
    }

    Map<Integer, Set<Integer>> getExcluded() {
        try (var ignored = Locks.acquire(lock)) {
            return Collections.unmodifiableMap(new LinkedHashMap<>(excluded));
        }
    }

    Set<Integer> getExcludedItems() {
        try (var ignored = Locks.acquire(lock)) {
            return Collections.unmodifiableSet(excludedItems);
        }
    }

    void commitExcludedItems() {
        Map<Integer, Set<Integer>> petExcluded = getExcluded();

        try (var ignored = Locks.acquire(lock)) {
            excludedItems.clear();
        }

        for (Map.Entry<Integer, Set<Integer>> pe : petExcluded.entrySet()) {
            byte petIndex = getPetIndex(pe.getKey());
            if (petIndex < 0) {
                continue;
            }

            Set<Integer> exclItems = pe.getValue();
            if (!exclItems.isEmpty()) {
                owner.getRemote().pet().loadExclusionList(owner, pe.getKey(), petIndex, new ArrayList<>(exclItems));

                try (var ignored = Locks.acquire(lock)) {
                    excludedItems.addAll(exclItems);
                }
            }
        }
    }

    void exportExcludedItems(Client c) {
        Map<Integer, Set<Integer>> petExcluded = getExcluded();
        for (Map.Entry<Integer, Set<Integer>> pe : petExcluded.entrySet()) {
            byte petIndex = getPetIndex(pe.getKey());
            if (petIndex < 0) {
                continue;
            }

            Set<Integer> exclItems = pe.getValue();
            if (!exclItems.isEmpty()) {
                c.getRemote().pet().loadExclusionList(owner, pe.getKey(), petIndex, new ArrayList<>(exclItems));
            }
        }
    }

    private void replacePetExcludedItemsInMemory(int petId, Collection<Integer> itemIds) {
        try (var ignored = Locks.acquire(lock)) {
            excluded.remove(petId);
            if (itemIds != null && !itemIds.isEmpty()) {
                LinkedHashSet<Integer> normalizedItems = itemIds.stream()
                        .filter(Objects::nonNull)
                        .collect(Collectors.toCollection(LinkedHashSet::new));
                if (!normalizedItems.isEmpty()) {
                    excluded.put(petId, normalizedItems);
                }
            }
        }
    }

    private void removeExcluded(int petId) {
        try (var ignored = Locks.acquire(lock)) {
            excluded.remove(petId);
        }
    }

    // ── 拾取瞬移上下文 ──

    /**
     * 记录传送前玩家坐标，供宠物拾取反作弊旧位置物品补偿使用。
     * 每次内传送门触发时由 InnerPortalHandler 调用。
     */
    void setPetLootTeleportBeforePos(Point pos) {
        this.petLootTeleportBeforePos = pos;
        this.petLootTeleportBeforePosTime = monotonicNow();
    }

    /** 获取宠物拾取补偿用的传送前坐标。1.5s 内有效，超时自动失效，避免旧坐标残留下一次捡包误判。 */
    Point getPetLootTeleportBeforePos() {
        if (petLootTeleportBeforePos == null) {
            return null;
        }
        if (monotonicNow() - petLootTeleportBeforePosTime > PET_LOOT_TELEPORT_CONTEXT_EXPIRE_NS) {
            petLootTeleportBeforePos = null;
            return null;
        }
        return new Point(petLootTeleportBeforePos);
    }

    private static long monotonicNow() {
        return System.nanoTime();
    }

    // ── 持久化 ──

    /** 角色保存主事务内调用：用传入连接保存全部宠物（消除第二写者，见 Pet.saveToDb(Connection)） */
    void saveToDb(Connection con) {
        List<Pet> petList;
        try (var ignored = Locks.acquire(lock)) {
            petList = new LinkedList<>(allPets.values());
        }

        for (Pet pet : petList) {
            pet.saveToDb(con);
        }
    }
}
