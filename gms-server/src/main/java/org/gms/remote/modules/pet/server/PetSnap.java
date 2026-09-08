package org.gms.remote.modules.pet.server;

/**
 * 宠物 wire 事实快照（版本中立）：版本实现在事件入域时从活 Pet 抽取（freeze），
 * 翻译层只读快照。字段集 = 宠物物品体/面板所需的全部宠物数据。
 * 见 gms-server/doc/package-client.md §1。
 */
public record PetSnap(long petId, String name, int level, int tameness, int fullness,
                      int flags, boolean alive, long expiration) {
}
