package org.gms.remote.gms083.server.routers;

import org.gms.client.character.Character;
import org.gms.client.character.MapView;
import org.gms.client.Skill;
import org.gms.client.status.MonsterStatus;
import org.gms.client.status.MonsterStatusEffect;
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
import org.gms.remote.gms083.server.blocks.MonsterBlock;
import org.gms.remote.gms083.server.packets.ControlMonsterPacket;
import org.gms.remote.gms083.server.packets.DropItemPacket;
import org.gms.remote.gms083.server.packets.KillMonsterPacket;
import org.gms.remote.gms083.server.packets.SpawnMonsterPacket;
import org.gms.remote.gms083.server.packets.StopControlMonsterPacket;
import org.gms.remote.gms083.server.packets.V83Packet;
import org.gms.remote.gms083.server.packets.SetFieldPacket;
import org.gms.remote.gms083.server.packets.ShowForeignEffectPacket;
import org.gms.remote.gms083.server.translators.Filetimes;
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
import org.gms.server.life.MobSkill;
import org.gms.server.life.MobSkillId;
import org.gms.server.maps.MapItem;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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
            Character chr = client.getLegacyClient().getPlayer();
            MapView.MonsterView view = chr.mapView().monster(e.oid());
            if (view == null) {
                // 响亮：授控帧数据源缺失 = 落地/入场 placement 未登记该 oid（装配洞）
                log.error("授控帧缺怪物值视图: oid={} (map={})", e.oid(), chr.getMapId(),
                        new RuntimeException("call site"));
                return new FrozenControlMonsterEvent(null);
            }
            return new FrozenControlMonsterEvent(controlFrame(view, e.immediateAggro(), false));
        }
        if (event instanceof MonsterSpawnEvent e) {
            return new FrozenMonsterSpawnEvent(spawnFrame(e));
        }
        if (event instanceof MonsterSpawnControlledEvent e) {
            // 测试版②：单 MONSTER_SPAWN_CONTROL，newSpawn=true（淡入出场位）
            return new FrozenControlMonsterEvent(controlFrame(e.view(), false, e.newSpawn()));
        }
        return event;
    }

    /**
     * 落地帧物化（legacy PacketCreator.spawnMonster / spawnFakeMonster 的逐位复刻；
     * 值源 = {@link MapView.MonsterView} post 时点快照，普通落地帧 stati 段由包结构跳过）。
     */
    private V83Packet spawnFrame(MonsterSpawnEvent e) {
        MapView.MonsterView view = e.view();
        if (e.fake()) {
            // 假怪帧 = CONTROL 头 mode 1 + kind 5 + temporary stati（spawnFakeMonster 逐位一致）
            MonsterBlock.Stati stati = statiOf(view.stati());
            return new ControlMonsterPacket((byte) 1, new MonsterBlock.Fake(view.oid(), view.mobId(),
                    stati.statuses(), stati.mask(), stati.tail(),
                    view.position(), view.stance(), view.fh(), view.team(),
                    e.effect()));
        }
        MonsterBlock.Spawn block = new MonsterBlock.Spawn(view.oid(),
                (byte) (view.controlled() ? 1 : 5), view.mobId(),
                view.position(), view.stance(), view.fh(), view.team(),
                e.newSpawn(), e.effect(), view.linkedParentOid());
        return new SpawnMonsterPacket(block);
    }

    /**
     * 授控全身帧物化（legacy PacketCreator.controlMonster → spawnMonsterInternal(control=true)
     * 的逐位复刻；值源 = {@link MapView.MonsterView} post 时点快照）。
     */
    private ControlMonsterPacket controlFrame(MapView.MonsterView view, boolean aggro, boolean newSpawn) {
        MonsterBlock.Stati stati = statiOf(view.stati());
        // controllerKind 恒 1：授控语义推导（grant 入队前 setController 已完成，legacy 活读
        // 除竞态窗外恒 1）——不依赖快照时点，竞态免疫
        return new ControlMonsterPacket((byte) (aggro ? 2 : 1),
                new MonsterBlock.Control(view.oid(), (byte) 1, view.mobId(),
                        stati.statuses(), stati.mask(), stati.tail(),
                        view.position(), view.stance(), view.fh(), view.team(),
                        newSpawn, view.linkedParentOid()));
    }

    /** temporary stati 提取（legacy encodeTemporary 输入侧；Control/Fake 帧共用） */
    private MonsterBlock.Stati statiOf(Map<MonsterStatus, MonsterStatusEffect> raw) {
        // stati：过滤 WATK/WDEF 后 toMap（HashMap 迭代序与 legacy 逐位一致）
        Map<MonsterStatus, MonsterStatusEffect> filtered = raw.entrySet().stream()
                .filter(e -> !(e.getKey() == MonsterStatus.WATK || e.getKey() == MonsterStatus.WDEF))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        int[] mask = new int[4];
        List<MonsterBlock.StatusEntry> entries = new ArrayList<>(filtered.size());
        int pCounter = -1;
        int mCounter = -1;
        for (Map.Entry<MonsterStatus, MonsterStatusEffect> s : filtered.entrySet()) {
            MonsterStatusEffect mse = s.getValue();
            MobSkill mobSkill = mse.getMobSkill();
            MonsterBlock.StatusEntry entry;
            if (mobSkill != null) {
                MobSkillId msId = mobSkill.getId();
                entry = new MonsterBlock.StatusEntry(
                        mse.getStati().get(s.getKey()).shortValue(), true,
                        (short) msId.type().getId(), (short) msId.level(), 0);
                switch (s.getKey()) {
                    case WEAPON_REFLECT -> pCounter = mobSkill.getX();
                    case MAGIC_REFLECT -> mCounter = mobSkill.getY();
                    default -> { }
                }
            } else {
                Skill skill = mse.getSkill();
                entry = new MonsterBlock.StatusEntry(
                        mse.getStati().get(s.getKey()).shortValue(), false,
                        (short) 0, (short) 0, skill != null ? skill.getId() : 0);
            }
            entries.add(entry);

            MonsterStatus statup = s.getKey();
            int pos = statup.isFirst() ? 0 : 2;
            for (int i = 0; i < 2; i++) {
                mask[pos + i] |= statup.getValue() >> 32 * i;
            }
        }
        MonsterBlock.ReflectTail tail = pCounter != -1 || mCounter != -1
                ? new MonsterBlock.ReflectTail(pCounter, mCounter) : MonsterBlock.ReflectTail.none();
        return new MonsterBlock.Stati(entries, mask, tail);
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
