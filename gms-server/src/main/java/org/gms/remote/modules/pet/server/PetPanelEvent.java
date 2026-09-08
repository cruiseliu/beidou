package org.gms.remote.modules.pet.server;

import org.gms.remote.ServerEvent;

/** 宠物面板快照（构造时冻结的 wire 事实，tameness 原样携带、截断在翻译层）；
 *  levelUp = 本次变更跨越等级边界（演出判定依据）。原 SemanticEvent.PetPanel。 */
public record PetPanelEvent(int cid, byte petIndex, short pos, long petId, int itemId, String name,
                            int level, int tameness, int fullness, int flags,
                            boolean alive, long expiration, boolean levelUp) implements ServerEvent {
}
