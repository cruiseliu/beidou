package org.gms.remote.modules.pet.server;

import java.util.List;

import org.gms.remote.ServerEvent;

/** 拾取过滤共享列表快照（构造时冻结）。帧按 commit 时刻的召唤集逐宠展开（协议 per-pet，配置共享）；
 *  原 SemanticEvent.PetIgnoreList。 */
public record PetIgnoreListEvent(int cid, List<Integer> itemIds) implements ServerEvent {
}
