package org.gms.remote.gms083.server.translators;

import org.gms.client.Skill;
import org.gms.client.character.MapView;
import org.gms.client.status.MonsterStatus;
import org.gms.client.status.MonsterStatusEffect;
import org.gms.remote.gms083.server.blocks.MonsterBlock;
import org.gms.remote.gms083.server.packets.ControlMonsterPacket;
import org.gms.remote.gms083.server.packets.SpawnMonsterPacket;
import org.gms.remote.gms083.server.packets.V83Packet;
import org.gms.server.life.MobSkill;
import org.gms.server.life.MobSkillId;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 怪物落地/授控帧翻译（值快照 → packet 树）：stati 位掩码规范化、controllerKind 推导、
 * 假怪/落地/授控三分。输入全部为 {@link MapView.MonsterView} 值快照与基础类型——
 * 零活引用、零状态读取；字节排布归 {@link MonsterBlock}。
 */
public final class MonsterTranslator {

    private MonsterTranslator() {
    }

    /** temporary stati 提取（legacy encodeTemporary 输入侧；Control/Fake 帧共用）：
     *  过滤 WATK/WDEF 后 toMap（HashMap 迭代序与 legacy 逐位一致）→ 掩码分段 + 条目 + 反击尾。 */
    public static MonsterBlock.Stati stati(Map<MonsterStatus, MonsterStatusEffect> raw) {
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

    /** 授控帧（MONSTER_SPAWN_CONTROL 0x01/0x02）：controllerKind 恒 1——授控语义推导
     *  （grant 入队前 setController 已完成），不依赖快照时点。 */
    public static ControlMonsterPacket control(MapView.MonsterView view, boolean aggro, boolean newSpawn) {
        MonsterBlock.Stati stati = stati(view.stati());
        return new ControlMonsterPacket((byte) (aggro ? 2 : 1),
                new MonsterBlock.Control(view.oid(), (byte) 1, view.mobId(),
                        stati.statuses(), stati.mask(), stati.tail(),
                        view.position(), view.stance(), view.fh(), view.team(),
                        newSpawn, view.linkedParentOid()));
    }

    /** 落地帧族：普通落地 = MONSTER_SPAWN；fake = CONTROL 头 kind 5 形态（legacy 同）。 */
    public static V83Packet spawn(MapView.MonsterView view, boolean newSpawn, int effect, boolean fake) {
        if (fake) {
            MonsterBlock.Stati stati = stati(view.stati());
            return new ControlMonsterPacket((byte) 1, new MonsterBlock.Fake(view.oid(), view.mobId(),
                    stati.statuses(), stati.mask(), stati.tail(),
                    view.position(), view.stance(), view.fh(), view.team(),
                    effect));
        }
        MonsterBlock.Spawn block = new MonsterBlock.Spawn(view.oid(),
                (byte) (view.controlled() ? 1 : 5), view.mobId(),
                view.position(), view.stance(), view.fh(), view.team(),
                newSpawn, effect, view.linkedParentOid());
        return new SpawnMonsterPacket(block);
    }
}
