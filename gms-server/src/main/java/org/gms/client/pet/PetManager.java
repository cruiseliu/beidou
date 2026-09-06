package org.gms.client.pet;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 宠物域 host（最小壳）：petId → Pet 的全局注册表（get-or-load 缓存）。
 * 收编原 {@code Pet.loadedPets} 静态字段；定时器已归各角色 strand（CharacterPets 的
 * KeyedTimers），本壳无线程、无定时任务、无所有权状态机——"在缓存且被某 client 持有"
 * 即事实所有权，移交语义在 {@link Pet}#bind/unbind。
 *
 * <p>DB 加载在壳的 putIfAbsent 竞争窗内进行（单机并发极低，锁分桶开销可忽略）；
 * phase 2 多客户端时此处升级为 petId → client 纯所有权映射（client 自读 DB）。
 */
public final class PetManager {
    private static final PetManager INSTANCE = new PetManager();

    public static PetManager get() {
        return INSTANCE;
    }

    private final Map<Integer, Pet> loaded = new ConcurrentHashMap<>();

    private PetManager() {
    }

    /** 取已加载宠物，未加载则从 DB 装载（并发首载由 putIfAbsent 去重，败者复用先到实例） */
    public Pet getOrLoad(int petId) {
        Pet pet = loaded.get(petId);
        if (pet != null) {
            return pet;
        }
        Pet created = new Pet(petId);
        created.loadFromDb();
        Pet raced = loaded.putIfAbsent(petId, created);
        return raced != null ? raced : created;
    }

    /** 登记新建宠物（petId 为全新生成，无竞争） */
    void register(Pet pet) {
        loaded.put(pet.getPetId(), pet);
    }

    /** 注销（destroy/dispose 路径；DB 行由调用方处理） */
    void unregister(int petId) {
        loaded.remove(petId);
    }
}
