package org.gms.client;

import org.gms.client.character.Character;
import org.gms.client.character.MapView;
import org.gms.client.messages.MapCharacterMoveMessage;
import org.gms.client.messages.MapControlMonsterMessage;
import org.gms.client.messages.MapItemDropMessage;
import org.gms.client.messages.MapMonsterDeathMessage;
import org.gms.client.messages.MapMonsterHpMessage;
import org.gms.client.messages.MapMonsterKilledMessage;
import org.gms.client.messages.MapMonsterSpawnMessage;
import org.gms.client.messages.MapMonsterMoveMessage;
import org.gms.client.messages.MapObjectSpawnMessage;
import org.gms.client.messages.MapObjectsViewMessage;
import org.gms.client.messages.MapQuestCompleteMessage;
import org.gms.infra.ActorMessage;
import org.gms.net.packet.Packet;
import org.gms.server.maps.MapObjectType;
import org.gms.server.maps.MapleMap;
import org.gms.util.PacketCreator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

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
            case MapCharacterMoveMessage m -> {
                // 接收方权威校验（他人流中继）：中继铸造于 map actor 的受众扫描，切图竞态下
                // 迟到中继（旧图他人移动）在此丢弃，不落到新图客户端
                Character chr = player.character();
                if (chr != null && chr.getMapId() == m.mapId()) {
                    player.remote().map().characterMove(m.charId(), m.movements());
                }
            }
            case MapQuestCompleteMessage m -> {
                // 接收方权威校验（他人流中继，同移动中继）
                Character chr = player.character();
                if (chr != null && chr.getMapId() == m.mapId()) {
                    player.remote().map().characterQuestComplete(m.charId());
                }
            }
            case MapMonsterMoveMessage m -> {
                // 接收方权威校验（他人流中继，同移动中继）
                Character chr = player.character();
                if (chr != null && chr.getMapId() == m.mapId()) {
                    player.remote().map().monsterMove(m.move());
                }
            }
            case MapMonsterKilledMessage m -> {
                // 接收方权威校验（同 HP 帧）：切图竞态下的迟到击杀结算在此丢弃
                Character chr = player.character();
                // 存活门（原 giveExpToCharacter 首行）：视图读，本域免哨
                if (chr != null && chr.getMapId() == m.mapId() && chr.isAlive()) {
                    player.clientEventHandlers().battle().monsterKilled(m.mobId(), m.mobLevel(),
                            m.expWeight(), m.partyBonusWeight(), m.white(), m.hasPartySharers(),
                            m.showdownMult(), m.familyRepGain());
                }
            }
            case MapMonsterHpMessage m -> {
                // 接收方权威校验（异步边界）：map 的投递解析基于陈旧玩家表，切图竞态下
                // 的迟到 HP 帧在此丢弃——唯一知道玩家当前时点所在图的是 Player actor。
                Character chr = player.character();
                if (chr != null && chr.getMapId() == m.mapId()) {
                    player.remote().map().updateMonsterHp(m.oid(), m.hpPercent());
                }
            }
            case MapObjectsViewMessage m -> {
                // 接收方权威校验（同 HP 帧）：切图竞态下的迟到可见集差集在此丢弃。
                // 纯状态应用（无 client 发送），map actor 各 spawn/destroy 投递点回投。
                Character chr = player.character();
                if (chr != null && chr.getMapId() == m.mapId()) {
                    chr.applyMapObjectsView(m.adds(), m.removes());
                }
            }
            case MapItemDropMessage m -> {
                // 掉落物落地（原 spawnDrop bakery 值化）：visible 判定（自身位置 × 落点，阈值同
                // map 侧静态）+ 过滤规则原样（needQuestItem），判定移 viewer 域（本体直读免哨）；
                // wire 构建与所有权演出归 map 域 remote。视图登记 = 包真正发出的对象（client 已知集）。
                Character chr = player.character();
                if (chr != null && chr.getMapId() == m.mapId() && chr.needQuestItem(m.questid(), m.itemId())
                        && chr.getPosition().distanceSq(m.dropto()) <= MapleMap.getRangedDistance()) {
                    player.remote().map().itemDropped(m.oid(), m.itemId(), m.meso(),
                            m.characterOwnerId(), m.partyOwnerId(), m.dropTime(), m.itemExpiration(),
                            m.dropType(), m.playerDrop(), m.dropperOid(), m.dropfrom(), m.dropto(), m.mod());
                    chr.applyMapObjectsView(List.of(new MapView.Entry(m.oid(),
                            new MapView.MapObjectInfo(MapObjectType.ITEM, m.itemId(), m.dropto(), true))), List.of());
                }
            }
            case MapControlMonsterMessage m -> {
                // 授控（原 aggro-control legacy 桥值化）：mapId 门（跨图竞态丢弃）。
                // mob 由消息携带（strict 管线内禁反查 map 本体）；无视野门：legacy 授控
                // 不检查 controller 视野。
                Character chr = player.character();
                if (chr != null && chr.getMapId() == m.mapId()) {
                    player.remote().map().controlMonster(m.mob(), m.aggro());
                }
            }
            case MapMonsterSpawnMessage m -> {
                // 怪物落地（原 spawnAndPostMapObject 预构建包值化）：visible 判定同预构建路径
                // （自身位置 × viewEntry 位置）——可见才登记视图并投落地帧，不可见整体丢弃
                // （move-diff 按需重发）。帧构建归 map 域 remote（freeze 物化）。
                Character chr = player.character();
                if (chr != null && chr.getMapId() == m.mapId()
                        && chr.getPosition().distanceSq(m.viewEntry().info().position()) <= MapleMap.getRangedDistance()) {
                    chr.applyMapObjectsView(List.of(m.viewEntry()), List.of());
                    player.remote().map().monsterSpawn(m.mob(), m.newSpawn(), m.effect(), m.fake());
                }
            }
            case MapMonsterDeathMessage m -> {
                // 怪物死亡场景事件（原 KILL_MONSTER ranged 广播值化）：可见判定 = MapView 成员
                // （client 已被告知此怪才需要死亡演出）——可见才投死亡演出并摘除视图登记，
                // 未知整体丢弃。结算（经验/任务计数/家族声望）不在此，走 MapMonsterKilledMessage。
                Character chr = player.character();
                if (chr != null && chr.getMapId() == m.mapId() && chr.mapView().contains(m.oid())) {
                    player.remote().map().monsterKilled(m.oid(), m.animation());
                    chr.applyMapObjectsView(List.of(), List.of(m.oid()));
                }
            }
            case MapObjectSpawnMessage m -> {
                // 对象落地（原 spawnAndAddRangedMapObject inRange 收集的值化）：visible 判定在
                // viewer 域——可见才直发预构建包并登记视图，不可见整体丢弃（move-diff 按需重发）。
                Character chr = player.character();
                if (chr != null && chr.getMapId() == m.mapId()
                        && chr.getPosition().distanceSq(m.viewEntry().info().position()) <= MapleMap.getRangedDistance()) {
                    chr.applyMapObjectsView(List.of(m.viewEntry()), List.of());
                    for (Packet packet : m.packets()) {
                        chr.sendPacket(packet);
                    }
                }
            }
            default -> log.warn("未知 actor 消息: {}", msg.name());   // 响亮：infra 不封闭，未知类型 = 装配漏配
        }
    }
}
