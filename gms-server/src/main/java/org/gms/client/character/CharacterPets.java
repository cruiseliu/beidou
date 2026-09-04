package org.gms.client.character;

import org.gms.client.pet.Pet;
import org.gms.client.inventory.InventoryType;
import org.gms.client.inventory.Item;
import org.gms.client.inventory.ItemPool;
import org.gms.client.inventory.ItemSlot;
import org.gms.client.inventory.manipulator.InventoryManipulator;
import org.gms.constants.inventory.ItemConstants;
import org.gms.model.json.CharacterPetsData;
import org.gms.remote.PetModule;
import org.gms.util.Locks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

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

    /** 召唤槽位上限 */
    private static final int MAX_SUMMONED = 3;

    private final Character owner;

    private final Map<Integer, Pet> allPets = new LinkedHashMap<>();  // petId -> pet
    private final List<Pet> summonedPets = new ArrayList<>();

    /** 拾取过滤共享列表（全角色宠物共用；按客户端提交序，随角色持久化） */
    private List<Integer> ignoreList = new ArrayList<>();  // NOTE: keep it unmodifiable

    private final Lock lock = new ReentrantLock(true);

    CharacterPets(Character owner) {
        this.owner = owner;
    }

    /** Free unused resources. */
    void dispose() {
        for (Pet pet : allPets.values()) {
            pet.dispose();
        }
    }

    // -- Ownership --

    /**
     * Grant a pet into the inventory.
     * The life duration is determined by the wz file.
     */
    public boolean grantPet(int itemId) {
        return grantPetInternal(itemId, null);
    }

    /**
     * Grant a pet and set its life duration.
     * If durationMs = -1, the pet will be permanent.
     */
    public boolean grantPet(int itemId, long durationMs) {
        return grantPetInternal(itemId, durationMs);
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
        // bind() is invoked by enter inventory hook
        if (!success) {
            // since we did not lock the inventory this might fail (rarely)
            pet.destroy();
        }
        return success;
    }

    Pet getPetById(int petId) {
        try (var _l = Locks.acquire(lock)) {
            return allPets.get(petId);
        }
    }

    /** Should only be called from Pet.bind() */
    public void registerPet(Pet pet) {
        try (var _l = Locks.acquire(lock)) {
            allPets.put(pet.getPetId(), pet);
        }
    }

    /** Should only be called from Pet.unbind() or similar. */
    public void unregisterPet(Pet pet) {
        try (var _l = Locks.acquire(lock)) {
            allPets.remove(pet.getPetId());
        }
    }

    /** Used by scripts. */
    public void handlePetEnterInventory(int petId) {
        Pet.load(petId).bind(this);
    }

    /** Used by scrpits. */
    public void handlePetLeaveInventory(int petId) {
        Pet.load(petId).unbind(this);
    }

    // -- Summoned --

    public List<Pet> getSummonedPets() {
        try (var _l = Locks.acquire(lock)) {
            return List.copyOf(summonedPets);
        }
    }

    public Pet getSummonedPet(int index) {
        try (var _l = Locks.acquire(lock)) {
            return index < summonedPets.size() ? summonedPets.get(index) : null;
        }
    }

    public boolean hasSummonedPet() {
        try (var _l = Locks.acquire(lock)) {
            return summonedPets.size() > 0;
        }
    }

    public int getSummonedPetIndex(Pet pet) {
        return getSummonedPetIndex(pet.getPetId());
    }

    int getSummonedPetIndex(int petId) {
        try (var _l = Locks.acquire(lock)) {
            for (int i = 0; i < summonedPets.size(); i++) {
                if (summonedPets.get(i).getPetId() == petId) {
                    return i;
                }
            }
            return -1;
        }
    }

    /** Should only be called from Pet.summon() */
    public void addSummonedPet(Pet pet, boolean atTail) {
        try (var _l = Locks.acquire(lock)) {
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
    }

    /** Should only be called from Pet.dismiss() */
    public void removeDismissedPet(Pet pet) {
        try (var _l = Locks.acquire(lock)) {
            summonedPets.removeIf(p -> p.getPetId() == pet.getPetId());
        }
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
        try (var _l = Locks.acquire(lock)) {
            return List.copyOf(ignoreList);
        }
    }

    public void setIgnoreList(List<Integer> items) {
        try (var _l = Locks.acquire(lock)) {
            ignoreList = List.copyOf(items);
        }
    }

    // -- Persistence --

    /** NOTE: Remember to call savePetsToDb() */
    public CharacterPetsData toData() {
        try (var _l = Locks.acquire(lock)) {
            CharacterPetsData data = new CharacterPetsData();
            data.summoned = new ArrayList<>();
            for (Pet summonedPet : summonedPets) {
                data.summoned.add(summonedPet.getPetId());
            }
            data.ignoreItems = List.copyOf(ignoreList);
            return data;
        }
    }

    public void applyData(CharacterPetsData data) {
        try (var _l = Locks.acquire(lock)) {
            ignoreList = new ArrayList<>(data.ignoreItems);
            for (int petId : data.summoned) {
                Pet pet = Pet.load(petId);
                pet.bind(this);
                if (pet.isAlive()) {
                    pet.summonSilently(true);
                }
            }
        }
    }

    /** The pets are stored in another table, so serializing `this` is not enough. */
    void savePetsToDb(Connection con) {
        try (var _l = Locks.acquire(lock)) {
            for (Pet pet : allPets.values()) {
                pet.saveToDb(con);
            }
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
        try (var _l = Locks.acquire(lock)) {
            Pet[] slots = new Pet[MAX_SUMMONED];
            for (int i = 0; i < summonedPets.size(); i++) {
                slots[i] = summonedPets.get(i);
            }
            return slots;
        }
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
