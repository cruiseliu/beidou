package org.gms.client.character;

import org.gms.client.Client;
import org.gms.client.pet.Pet;
import org.gms.client.pet.PetDataFactory;
import org.gms.client.inventory.InventoryType;
import org.gms.client.inventory.ItemSlot;
import org.gms.client.inventory.manipulator.InventoryManipulator;
import org.gms.constants.id.ItemId;
import org.gms.constants.inventory.ItemConstants;
import org.gms.manager.ServerManager;
import org.gms.net.server.Server;
import org.gms.service.InventoryService;
import org.gms.server.ItemInformationProvider;
import org.gms.util.I18nUtil;
import org.gms.util.Locks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Point;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

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
    private static final Logger log = LoggerFactory.getLogger(CharacterPets.class);
    private static final InventoryService inventoryService = ServerManager.getApplicationContext().getBean(InventoryService.class);
    private static final long PET_LOOT_TELEPORT_CONTEXT_EXPIRE_NS = MILLISECONDS.toNanos(1500L);

    private final Character owner;

    /** 三个召唤槽位（沿用旧名 pets） */
    private final Pet[] activePets = new Pet[3];

    /** 全量宠物：petid → Pet（角色负责管理；与物品只靠 petid 关联，互不存引用） */
    private final Map<Integer, Pet> allPets = new LinkedHashMap<>();

    /** 宠物模块锁：串行化槽位/过滤配置/拾取上下文 */
    private final Lock lock = new ReentrantLock(true);

    /** 本角色已生效的屏蔽道具ID集（客户端加载列表用） */
    private final Set<Integer> excludedItems = new LinkedHashSet<>();

    /** 宠物拾取补偿用传送前坐标（1.5s 有效） */
    private Point petLootTeleportBeforePos = null;
    private long petLootTeleportBeforePosTime = 0;

    CharacterPets(Character owner) {
        this.owner = owner;
    }

    // ── 全量管理 ──

    /** 宠物登记（Pet 对象初始化时自行调用） */
    public void registerPet(Pet pet) {
        try (var ignored = Locks.acquire(lock)) {
            allPets.put(pet.getPetId(), pet);
            if (pet.isSummoned()) {
                // this is only possible on login
                // since character is initialized before entering map,
                // the map is responsible for the real summon
                if (getNoPets() < 3) {
                    addPet(pet);
                    commitExcludedItems();
                } else {
                    log.error("register summoned {} without empty slot", pet);
                }
            }
        }
    }

    /** 按 petid 查询全量宠物（物品→宠物方向；未登记返回 null） */
    Pet getPetById(int petid) {
        try (var ignored = Locks.acquire(lock)) {
            return allPets.get(petid);
        }
    }

    /** 宠物注销（物品删除时调用；同步移出召唤槽） */
    public void unregisterPet(int petid) {
        Pet pet;
        try (var ignored = Locks.acquire(lock)) {
            pet = allPets.remove(petid);
            for (int i = 0; i < 3; i++) {
                if (activePets[i] != null && activePets[i].getPetId() == petid) {
                    activePets[i] = null;
                }
            }
        }
        // if (pet != null) {
        //     pet.onDetach();   // 驻留注销事件：对象自清（到期任务等）
        // }
    }

    public void unregisterPet(Pet pet) {
        unregisterPet(pet.getPetId());
    }

    void dispose() {
        for (Pet pet : allPets.values()) {
            pet.dispose();
        }
    }

    /**
     * 驻留登记（JS hook onEnterInventory 的落点）：宿主物品进入本角色背包。
     * 幂等：已驻留 no-op。summoned=1 的宠物恢复召唤槽（≤3）并加载过滤配置。
     * 到期已过的宠物先补算到期策略（离线到期不依赖定时器）。
     */
    public void handlePetEnterInventory(int petId) {
        Pet.load(petId).bind(this);
    }

    /**
     * 驻留注销（JS hook onLeaveInventory 的落点）：宿主物品离开本角色背包
     * （存现金仓库/移除等）。召唤中则先下阵。幂等：未驻留 no-op。
     */
    public void handlePetLeaveInventory(int petId) {
        Pet.load(petId).unbind(this);
    }

    // ── 生命周期：授予与孵化（doc/11 §5）──

    /**
     * 发放宠物入本角色背包，持续时长取 wz info/life（脚本独立 API gainPet 的落点）：
     * 建 pets 行 → 物品带 petid 入包（enter 钩子 adopt 登记）→ 入包失败补偿删行。
     */
    public boolean grantPet(int itemId) {
        return grant(Pet.create(itemId));
    }

    /** 发放指定持续时长（毫秒，<=0 = 永久）的宠物 */
    public boolean grantPet(int itemId, long durationMs) {
        return grant(Pet.create(itemId, durationMs));
    }

    private boolean grant(Pet pet) {
        int petId = pet.getPetId();
        if (!InventoryManipulator.REFACTOR6_addById(owner.getClient(), pet.getItemId(), (short) 1, null, petId, -1)) {
            ServerManager.getApplicationContext().getBean(InventoryService.class).deletePetData(petId);
            return false;
        }
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
        // 名字未被玩家改过则随形态重置（蛋名"进化龙"→ 新形态默认名；官方语义）
        String defaultOld = ItemInformationProvider.getInstance().getName(pet.getItemId());
        if (pet.getName() != null && pet.getName().equals(defaultOld)) {
            pet.setName(ItemInformationProvider.getInstance().getName(newItemId));
        }
        pet.evolve(newItemId);   // 物种刷新并落库（名字/物种一次写库；cached 对象的 itemId 必须同步，否则每次召唤都重复孵化）
        // 驻留交由钩子收敛：remove 排队 Leave（release，顺带落库新名），add 侧自愈装载并登记，
        // 随后的 Enter(adopt) 幂等 no-op——net 线程不做手工登记，避免与脚本队列竞态
        InventoryManipulator.removeFromSlot(c, InventoryType.CASH, (short) host.getPosition(), (short) 1, false);
        if (!InventoryManipulator.REFACTOR6_addById(c, newItemId, (short) 1, null, petId, -1)) {
            // 理论上 checkSpace 后不会到这；兜底回插旧形态，避免宿主悬空
            InventoryManipulator.REFACTOR6_addById(c, pet.getItemId(), (short) 1, null, petId, -1);
            return false;
        }
        return true;
    }

    // ── 槽位管理 ──
    public void addPet(Pet pet) {
        try (var ignored = Locks.acquire(lock)) {
            for (int i = 0; i < 3; i++) {
                if (activePets[i] == null) {
                    activePets[i] = pet;
                    return;
                }
            }
        }
    }

    public void removePet(Pet pet, boolean shift_left) {
        try (var ignored = Locks.acquire(lock)) {
            int slot = -1;
            for (int i = 0; i < 3; i++) {
                if (activePets[i] != null) {
                    if (activePets[i].getPetId() == pet.getPetId()) {
                        activePets[i] = null;
                        slot = i;
                        break;
                    }
                }
            }
            if (shift_left) {
                if (slot > -1) {
                    for (int i = slot; i < 3; i++) {
                        if (i != 2) {
                            activePets[i] = activePets[i + 1];
                        } else {
                            activePets[i] = null;
                        }
                    }
                }
            }
        }
    }

    void shiftPetsRight() {
        try (var ignored = Locks.acquire(lock)) {
            if (activePets[2] == null) {
                activePets[2] = activePets[1];
                activePets[1] = activePets[0];
                activePets[0] = null;
            }
        }
    }

    int getNoPets() {
        try (var ignored = Locks.acquire(lock)) {
            int ret = 0;
            for (int i = 0; i < 3; i++) {
                if (activePets[i] != null) {
                    ret++;
                }
            }
            return ret;
        }
    }

    Pet[] getActivePets() {
        try (var ignored = Locks.acquire(lock)) {
            return Arrays.copyOf(activePets, activePets.length);
        }
    }

    public List<Pet> getSummonedPets() {
        List<Pet> ret = new ArrayList<>();
        for (Pet pet : activePets) {
            if (pet != null) {
                ret.add(pet);
            }
        }
        return ret;
    }

    Pet getPet(int index) {
        if (index < 0) {
            return null;
        }
        try (var ignored = Locks.acquire(lock)) {
            return activePets[index];
        }
    }

    byte getPetIndex(int petId) {
        try (var ignored = Locks.acquire(lock)) {
            for (byte i = 0; i < 3; i++) {
                if (activePets[i] != null) {
                    if (activePets[i].getPetId() == petId) {
                        return i;
                    }
                }
            }
            return -1;
        }
    }

    public byte getPetIndex(Pet pet) {
        try (var ignored = Locks.acquire(lock)) {
            for (byte i = 0; i < 3; i++) {
                if (activePets[i] != null) {
                    if (activePets[i].getPetId() == pet.getPetId()) {
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

    // ── 生命周期 ──

    private void unEquipPet(Pet pet) {
        boolean shift_left = true;
        boolean hunger = false;

        byte petIdx = getPetIndex(pet);
        Pet chrPet = getPet(petIdx);

        if (chrPet != null) {
            chrPet.setSummoned(false);
            chrPet.saveToDb();
        }

        owner.getRemote().pet().desummonPet(owner, chrPet, hunger);

        removePet(pet, shift_left);
        commitExcludedItems();

        owner.enableActions();
    }

    // ── 过滤配置（数据随 Pet 本体，Pet.setExcludes；此处只做驻留侧汇总与下发） ──

    /** 客户端提交过滤设置：写入 Pet 本体（立即落库）后重发本角色的过滤列表 */
    void updatePetExcludedItems(int petId, Set<Integer> newExcludedItems) {
        Pet pet = getPetById(petId);
        if (pet != null) {
            pet.setIgnoreItems(new ArrayList<>(newExcludedItems));
        }
        commitExcludedItems();
    }

    Set<Integer> getExcludedItems() {
        try (var ignored = Locks.acquire(lock)) {
            return Collections.unmodifiableSet(excludedItems);
        }
    }

    public void commitExcludedItems() {
        try (var ignored = Locks.acquire(lock)) {
            excludedItems.clear();
        }

        for (Pet pet : activePets) {
            if (pet == null || pet.getIgnoreItems().isEmpty()) {
                continue;
            }
            byte petIndex = getPetIndex(pet);
            if (petIndex < 0) {
                continue;
            }
            owner.getRemote().pet().loadExclusionList(owner, pet.getPetId(), petIndex, new ArrayList<>(pet.getIgnoreItems()));
            try (var ignored = Locks.acquire(lock)) {
                excludedItems.addAll(pet.getIgnoreItems());
            }
        }
    }

    void exportExcludedItems(Client c) {
        for (Pet pet : activePets) {
            if (pet == null || pet.getIgnoreItems().isEmpty()) {
                continue;
            }
            byte petIndex = getPetIndex(pet);
            if (petIndex < 0) {
                continue;
            }
            c.getRemote().pet().loadExclusionList(owner, pet.getPetId(), petIndex, new ArrayList<>(pet.getIgnoreItems()));
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

    public void handleExpire(Pet pet) {
        int index = getPetIndex(pet);
        if (index >= 0) {
            unEquipPet(pet);
        }
        // 客户端不会自行按本地时钟判过期（过期显示 = wire 携带 EXPIRED 哨兵），到期必须主动刷新物品体
        ItemSlot host = owner.findPetItemSlot(pet.getPetId());
        if (host != null) {
            owner.forceUpdateItem(host);  // fixme: [refactor] v83 should be responsible for this
        }
    }

    public void handleRevive(Pet pet) {
        ItemSlot host = owner.findPetItemSlot(pet.getPetId());
        if (host != null) {
            owner.forceUpdateItem(host);  // fixme: [refactor] v83 should be responsible for this
        }
    }

    public void handleDestroy(Pet pet) {
        ItemSlot host = owner.findPetItemSlot(pet.getPetId());
        if (host != null) {
            InventoryManipulator.removeFromSlot(owner.getClient(), InventoryType.CASH, (short) host.getPosition(), (short) 1, false);
        }
        unregisterPet(pet);
    }

    public Character getPlayer() {
        return owner;
    }

    public void handleLevelUp(Pet pet) {
        int index = getPetIndex(pet);
        if (index >= 0) {
            owner.getRemote().pet().petLevelUp(owner, index);
        } else {
            log.warn("Inactive pet %d level up", pet.getPetId());
        }
    }

    public int getCharacterId() {
        return owner.id;
    }
}
