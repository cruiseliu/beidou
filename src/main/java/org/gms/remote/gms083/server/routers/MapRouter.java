package org.gms.remote.gms083.server.routers;

import org.gms.client.character.Character;
import org.gms.client.character.MapView;
import org.gms.net.server.Server;
import org.gms.remote.ServerEvent;
import org.gms.remote.ServerEventBase;
import org.gms.remote.ServerEventDest;
import org.gms.remote.gms083.Gms083;
import org.gms.remote.gms083.client.packets.MoveLifePacket;
import org.gms.remote.gms083.client.packets.MovePlayerPacket;
import org.gms.remote.gms083.server.events.FrozenControlMonsterEvent;
import org.gms.remote.gms083.server.events.FrozenItemDropEvent;
import org.gms.remote.gms083.server.events.FrozenMonsterSpawnEvent;
import org.gms.remote.gms083.server.packets.DropItemPacket;
import org.gms.remote.gms083.server.packets.KillMonsterPacket;
import org.gms.remote.gms083.server.packets.StopControlMonsterPacket;
import org.gms.remote.gms083.server.packets.SetFieldPacket;
import org.gms.remote.gms083.server.packets.ShowForeignEffectPacket;
import org.gms.remote.gms083.server.translators.Filetimes;
import org.gms.remote.gms083.server.translators.MonsterTranslator;
import org.gms.remote.modules.map.MapModule;
import org.gms.remote.modules.map.server.AckMoveMonsterEvent;
import org.gms.remote.modules.map.server.ChangeMapServerEvent;
import org.gms.remote.modules.map.server.CharacterMoveEvent;
import org.gms.remote.modules.map.server.CharacterQuestCompleteEvent;
import org.gms.remote.modules.map.server.ControlMonsterEvent;
import org.gms.remote.modules.map.server.ItemDropEvent;
import org.gms.remote.modules.map.server.MonsterKilledEvent;
import org.gms.remote.modules.map.server.MonsterMoveEvent;
import org.gms.remote.modules.map.server.MonsterSpawnControlledEvent;
import org.gms.remote.modules.map.server.MonsterSpawnEvent;
import org.gms.remote.modules.map.server.StopControlMonsterEvent;
import org.gms.server.maps.MapItem;

/**
 * 地图域 route：出脸继承自 {@link MapModule}（移动中继语义事件），本类承载 emit/freeze/deliver/flush——
 * 元素序列对称重放为 wire 包；收播过滤已由地图侧在事件受众上决定，deliver 只管本连接编码。
 */
public final class MapRouter extends MapModule implements ServerEventDest {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(MapRouter.class);

    private final Gms083 client;

    public MapRouter(Gms083 client) {
        this.client = client;
    }

    @Override
    protected void emit(ServerEventBase event) {
        client.schedule(this, event);
    }

    /** 统一冻结门：掉落所有权演出与授控全身帧在入域时点物化（其余事件恒等通过） */
    @Override
    protected ServerEventBase freeze(ServerEvent event) {
        if (event instanceof ItemDropEvent d) {
            Character viewer = client.getLegacyClient().getPlayer();
            byte dropType = d.dropType();
            if (MapItem.hasClientsideOwnership(d.characterOwnerId(), d.partyOwnerId(), d.dropTime(), viewer)
                    && dropType < 3) {
                dropType = 2;
            }
            return new FrozenItemDropEvent(d.oid(), d.itemId(), d.meso(),
                    MapItem.clientsideOwnerId(d.characterOwnerId(), d.partyOwnerId()),
                    dropType, d.playerDrop(), d.dropperOid(), d.itemExpiration(),
                    d.dropfrom(), d.dropto(), d.mod());
        }
        if (event instanceof ControlMonsterEvent e) {
            // 授控帧数据源 = 接收方自持 map view（freeze 取数点）
            MapView.MonsterView view = client.getLegacyClient().getPlayer().mapView().monster(e.oid());
            if (view == null) {
                // 响亮：授控帧数据源缺失 = 落地/入场 placement 未登记该 oid（装配洞）
                log.error("授控帧缺怪物值视图: oid={} (map={})", e.oid(), client.getLegacyClient().getPlayer().getMapId(),
                        new RuntimeException("call site"));
                return new FrozenControlMonsterEvent(null);
            }
            return new FrozenControlMonsterEvent(MonsterTranslator.control(view, e.immediateAggro(), false));
        }
        if (event instanceof MonsterSpawnEvent e) {
            return new FrozenMonsterSpawnEvent(MonsterTranslator.spawn(e.view(), e.newSpawn(), e.effect(), e.fake()));
        }
        if (event instanceof MonsterSpawnControlledEvent e) {
            // 测试版②：单 MONSTER_SPAWN_CONTROL，newSpawn=true（淡入出场位）
            return new FrozenControlMonsterEvent(MonsterTranslator.control(e.view(), false, e.newSpawn()));
        }
        return event;
    }

    @Override
    public void deliver(ServerEventBase r) {
        switch (r) {
            case CharacterMoveEvent(var charId, var movements) ->
                    client.send(MovePlayerPacket.relay(charId, movements));
            case CharacterQuestCompleteEvent(var charId) ->
                    // 效果码 9 = 任务完成（他人流；本人帧归 quest 域 QuestCompleteEvent）
                    client.send(new ShowForeignEffectPacket(new ShowForeignEffectPacket.Body.Effect(charId, (byte) 9)));
            case MonsterMoveEvent(var move) ->
                    client.send(new MoveLifePacket.Relay(move.oid(), move.skillPossible(), move.skill(),
                            move.skillId(), move.skillLevel(), move.pOption(), move.startPos(), move.elements()));
            case AckMoveMonsterEvent(var oid, var moveid, var currentMp, var useSkills, var skillId, var skillLevel) ->
                    client.send(new MoveLifePacket.Response(oid, moveid, currentMp, useSkills, skillId, skillLevel));
            case org.gms.remote.modules.map.server.UpdateMonsterHpEvent(var oid, var hpPercent) ->
                    client.send(new org.gms.remote.gms083.server.packets.ShowMonsterHpPacket(oid, hpPercent));
            case MonsterKilledEvent(var oid, var animation) ->
                    client.send(new KillMonsterPacket(oid, animation));
            case StopControlMonsterEvent(var oid) ->
                    client.send(new StopControlMonsterPacket(oid));
            case FrozenControlMonsterEvent f -> {
                if (f.packet() != null) {   // null = freeze 侧视图缺失（已记 error），静默跳过
                    client.send(f.packet());
                }
            }
            case FrozenMonsterSpawnEvent f ->
                    client.send(f.packet());
            case FrozenItemDropEvent d ->
                    client.send(new DropItemPacket(d.oid(), d.itemId(), d.meso(), d.ownerId(), d.dropType(),
                            d.playerDrop(), d.dropperOid(), Filetimes.toWire(d.itemExpiration()),
                            d.dropfrom(), d.dropto(), d.mod()));
            case ChangeMapServerEvent(var mapId, var spawnPoint, var hp, var spawnPosition) ->
                    client.send(new SetFieldPacket.Warp(client.getLegacyClient().getChannel() - 1, mapId,
                            spawnPosition != null ? 0x80 : spawnPoint, hp, spawnPosition != null,
                            spawnPosition != null ? spawnPosition.x : 0,
                            spawnPosition != null ? spawnPosition.y : 0,
                            Filetimes.toWire(Server.getInstance().getCurrentTime())));
            default -> { }   // 非本模块事件不会到达（owner 标记保证）；防御静默
        }
    }

    @Override
    public void flush() {
    }
}
