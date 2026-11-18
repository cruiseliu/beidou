package org.gms.remote.gms083.server.translators;

import org.gms.client.character.MonsterView;
import org.gms.client.status.MonsterStatus;
import org.gms.remote.gms083.server.blocks.MonsterBlock;
import org.gms.remote.gms083.server.packets.ControlMonsterPacket;
import org.gms.remote.gms083.server.packets.SpawnMonsterPacket;
import org.gms.remote.gms083.server.packets.V83Packet;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 怪物落地/授控帧翻译（纯值快照 → packet 树）：stati 位掩码规范化、controllerKind 推导、
 * 假怪/落地/授控三分。输入全部为 {@link MonsterView} 的纯值快照与基础类型——零活引用、
 * 零状态读取；字节排布归 {@link MonsterBlock}。
 */
public final class MonsterTranslator {

    private MonsterTranslator() {
    }

    /** temporary stati 提取（legacy encodeTemporary 输入侧；Control/Fake 帧共用）：
     *  过滤 WATK/WDEF 后 toMap（HashMap 迭代序与 legacy 逐位一致）→ 掩码分段 + 条目 + 反击尾。 */
    public static MonsterBlock.Stati stati(List<MonsterView.StatusSnapshot> raw) {
        Map<MonsterStatus, MonsterView.StatusSnapshot> filtered = raw.stream()
                .filter(s -> s.status() != MonsterStatus.WATK && s.status() != MonsterStatus.WDEF)
                .collect(Collectors.toMap(MonsterView.StatusSnapshot::status, s -> s));

        int[] mask = new int[4];
        List<MonsterBlock.StatusEntry> entries = new ArrayList<>(filtered.size());
        int pCounter = -1;
        int mCounter = -1;
        for (Map.Entry<MonsterStatus, MonsterView.StatusSnapshot> s : filtered.entrySet()) {
            MonsterView.StatusSnapshot snap = s.getValue();
            MonsterBlock.StatusEntry entry;
            if (snap.fromMobSkill()) {
                entry = new MonsterBlock.StatusEntry(
                        snap.value(), true, snap.mobSkillType(), snap.mobSkillLevel(), 0);
                switch (s.getKey()) {
                    case WEAPON_REFLECT -> pCounter = snap.mobSkillX();
                    case MAGIC_REFLECT -> mCounter = snap.mobSkillY();
                    default -> { }
                }
            } else {
                entry = new MonsterBlock.StatusEntry(
                        snap.value(), false, (short) 0, (short) 0, snap.skillId());
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
    public static ControlMonsterPacket control(MonsterView view, boolean aggro, boolean newSpawn) {
        MonsterBlock.Stati stati = stati(view.stati());
        return new ControlMonsterPacket((byte) (aggro ? 2 : 1),
                new MonsterBlock.Control(view.oid(), (byte) 1, view.mobId(),
                        stati.statuses(), stati.mask(), stati.tail(),
                        view.position(), view.stance(), view.fh(), view.team(),
                        newSpawn, view.linkedParentOid()));
    }

    /** 落地帧族：普通落地 = MONSTER_SPAWN；fake = CONTROL 头 kind 5 形态（legacy 同）。 */
    public static V83Packet spawn(MonsterView view, boolean newSpawn, int effect) {
        if (view.fake()) {
            MonsterBlock.Stati stati = stati(view.stati());
            return new ControlMonsterPacket((byte) 1, new MonsterBlock.Fake(view.oid(), view.mobId(),
                    stati.statuses(), stati.mask(), stati.tail(),
                    view.position(), view.stance(), view.fh(), view.team(),
                    effect));
        }
        MonsterBlock.Spawn block = new MonsterBlock.Spawn(view.oid(),
                (byte) (view.controllerId() != 0 ? 1 : 5), view.mobId(),
                view.position(), view.stance(), view.fh(), view.team(),
                newSpawn, effect, view.linkedParentOid());
        return new SpawnMonsterPacket(block);
    }
}
