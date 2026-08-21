package org.gms.client.character;

import org.gms.server.Storage;

/**
 * 仓库模块组件：仓库实例（storage）+ 脏标记（usedStorage，变更后存档时落库）。
 * 仿照 CharacterBuffs/CharacterChair 模式：数据 + 领域逻辑内聚于此，持有 owner 反向引用，
 * Character 保留公开具名门面（getStorage/setUsedStorage/... 对外转发）。
 *
 * 边界：只承载仓库语义——仓库实例引用与变更标记。
 * 持久化 SQL（storage 表）留在 Character.saveCharToDB，数据访问经组件。
 */
class CharacterStorage {
    /** 仓库变更标记（仓库操作后置位，saveCharToDB 时落库并复位） */
    private boolean usedStorage = false;

    /** 仓库实例 */
    private Storage storage = null;

    CharacterStorage() {
    }

    // ── 查询 ──

    Storage getStorage() {
        return storage;
    }

    void setStorage(Storage storage) {
        this.storage = storage;
    }

    /** 仓库是否有未落库变更（saveCharToDB 用；包内可见） */
    boolean getUsedStorage() {
        return usedStorage;
    }

    void setUsedStorage() {
        usedStorage = true;
    }

    void resetUsedStorage() {
        usedStorage = false;
    }
}
