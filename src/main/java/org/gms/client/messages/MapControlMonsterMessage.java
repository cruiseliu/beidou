package org.gms.client.messages;

import org.gms.client.character.MapView;
import org.gms.infra.ActorMessage;

/**
 * map → player：授控通知（原 aggro-control legacy 桥的值化）。载荷 = 授控意图 +
 * {@link MapView.MonsterView} 值快照（map 域 post 时点冻结；setController 先于 post 同线程
 * 程序序，controlled 恒真——legacy 桥体读 controller 的竞态窗随之消除）。
 * mapId 供接收方校验自身所在图。
 */
public record MapControlMonsterMessage(int mapId, boolean aggro, MapView.MonsterView view) implements ActorMessage {
}
