/**
 * 宠物道具钩子（data/item/pets.json 全部宠物道具共享此模块）。
 *
 * 容器事件转发到角色宠物组件（CharacterPets）：inventory 只感知"带 petId 的物品
 * 进出背包"这一容器事实，pet 的全部游戏语义（驻留登记/注销、召唤恢复/解除）在 Java 侧。
 */
export function onEnterInventory(character, item, isLogin) {
    character.getPets().handlePetEnterInventory(item.getPetId());
}

export function onLeaveInventory(character, item, isLogout) {
    if (isLogout) {
        // 登出无清场：summoned 状态随保存持久化，下次登录 adopt 恢复召唤槽
        return;
    }
    character.getPets().handlePetLeaveInventory(item.getPetId());
}
