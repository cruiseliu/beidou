package org.gms.client;

import org.gms.client.character.Character;
import org.gms.client.messages.MapCharacterMoveMessage;
import org.gms.client.messages.MapQuestCompleteMessage;
import org.gms.infra.ActorMessage;
import org.gms.util.PacketCreator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * player actor 的类型化消息分发器：跨 actor 消息（{@link ActorMessage}）投递到
 * player strand 后在此路由到 player 域 handler——投递语义回到消息所属域内执行
 * （所有权干净，map 任务体零对端状态触达）。消息类型增长后演进为槽位注册
 * （对齐 ClientEventHandlerRegistry 先例）；当前硬编码 switch。
 */
public final class MessageDispatcher {
    private static final Logger log = LoggerFactory.getLogger(MessageDispatcher.class);

    private final Player player;

    public MessageDispatcher(Player player) {
        this.player = player;
    }

    public void dispatch(ActorMessage msg) {
        switch (msg) {
            case MapCharacterMoveMessage m -> player.remote().map().characterMove(m.charId(), m.movements());
            case MapQuestCompleteMessage m -> player.remote().map().characterQuestComplete(m.charId());
            case org.gms.client.messages.MapMonsterMoveMessage m -> player.remote().map().monsterMove(m.move());
            case org.gms.client.messages.MapMonsterKilledMessage m -> {
                // 接收方权威校验（同 HP 帧）：切图竞态下的迟到击杀结算在此丢弃
                Character chr = player.character();
                if (chr != null && chr.getMapId() == m.mapId()) {
                    player.clientEventHandlers().battle().monsterKilled(m.mobId(), m.mobLevel(),
                            m.expWeight(), m.partyBonusWeight(), m.white(), m.hasPartySharers(), m.showdownMult());
                }
            }
            case org.gms.client.messages.MapMonsterHpMessage m -> {
                // 接收方权威校验（异步边界）：map 的投递解析基于陈旧玩家表，切图竞态下
                // 的迟到 HP 帧在此丢弃——唯一知道玩家当前时点所在图的是 Player actor。
                Character chr = player.character();
                if (chr != null && chr.getMapId() == m.mapId()) {
                    player.remote().map().updateMonsterHp(m.oid(), m.hpPercent());
                }
            }
            case org.gms.client.messages.MapObjectsViewMessage m -> {
                // 接收方权威校验（同 HP 帧）：切图竞态下的迟到可见集差集在此丢弃。
                // 纯状态应用（无 client 发送），map actor 各 spawn/destroy 投递点回投。
                Character chr = player.character();
                if (chr != null && chr.getMapId() == m.mapId()) {
                    chr.applyMapObjectsView(m.adds(), m.removes());
                }
            }
            case org.gms.client.messages.MapItemDropMessage m -> {
                // 掉落物落地（原 spawnDrop bakery 值化）：过滤规则原样（needQuestItem），判定移
                // viewer 域（本体直读免哨）；构包走值核（PacketCreator），本域直发。
                Character chr = player.character();
                if (chr != null && chr.getMapId() == m.mapId() && chr.needQuestItem(m.questid(), m.itemId())) {
                    chr.sendPacket(PacketCreator.dropItemFromMapObject(chr, m.oid(), m.itemId(), m.meso(),
                            m.characterOwnerId(), m.partyOwnerId(), m.dropTime(), m.itemExpiration(),
                            m.dropType(), m.playerDrop(), m.dropperOid(), m.dropfrom(), m.dropto(), m.mod()));
                }
            }
            default -> log.warn("未知 actor 消息: {}", msg.name());   // 响亮：infra 不封闭，未知类型 = 装配漏配
        }
    }
}
