package org.gms.client.messages;

import org.gms.infra.ActorMessage;
import org.gms.server.life.Monster;

/**
 * map → player：授控通知（原 aggro-control legacy 桥的值化）。
 *
 * <p>载荷含 {@link Monster} 活引用——值化纪律的显式豁免：接收方不反查 map 本体
 * （strict 管线内 MapleMapRef 解引用被禁），freeze 快照读点 = legacy 桥体执行读点，
 * 同 strand 同相位，竞态语义不变。P3 player 域 controlled-set 落地后可收敛为纯值。
 * mapId 供接收方校验自身所在图。
 */
public record MapControlMonsterMessage(int mapId, int oid, boolean aggro, Monster mob) implements ActorMessage {
}
