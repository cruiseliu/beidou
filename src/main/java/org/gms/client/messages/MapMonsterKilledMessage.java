package org.gms.client.messages;

import org.gms.infra.ActorMessage;

/**
 * map → player：视野型怪物死亡场景事件（原 killMonster 的 KILL_MONSTER ranged 广播值化）。
 * **全员投递的场景事件**，非结算单——结算（经验/任务计数/家族声望）走
 * {@link MapMonsterKilledRewardMessage}（仅参与者）。接收方以 MapView.contains(oid) 判定
 * 「client 已被告知此怪」：已知才向 client 转发 kill 包（死亡演出）并摘除视图登记，
 * 未知整体丢弃（没见过就不需要死亡演出）。mapId 供接收方校验自身所在图。
 */
public record MapMonsterKilledMessage(
    int mapId,
    int oid,
    int animation
) implements ActorMessage {}
