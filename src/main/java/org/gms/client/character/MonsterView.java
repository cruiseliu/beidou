package org.gms.client.character;

import org.gms.client.status.MonsterStatus;

import java.awt.Point;
import java.util.List;

/**
 * 怪物值快照：落地/授控帧所需的全部 mob 字段，由 map 域在 post/登记时点通过
 * {@link org.gms.server.life.Monster#view()} 构造。
 *
 * <p>自包含纪律：本记录不携带 {@code Monster} 或 {@code MonsterStatusEffect} 活引用——
 * {@code position} 防御性拷贝，{@code stati} 是 {@link StatusSnapshot} 列表（值字段），
 * 消费方（player 域 / remote translator）只读本快照，不再触达 mob 活状态。
 *
 * <p>{@code controllerId} = 快照时点控制者的角色 id，0 = 无控制者。注意快照时点：
 * spawn 路径在静默选举之前取快照，因此新刷的怪恒为 0（落地帧 controllerKind=5）；
 * 换届/入场 placement 路径在选举之后取快照，反映真实结果。
 */
public record MonsterView(
        int oid,
        int mobId,
        int controllerId,
        Point position,
        byte stance,
        short fh,
        byte team,
        boolean fake,
        List<StatusSnapshot> stati,
        int linkedParentOid) {

    public MonsterView {
        position = new Point(position);   // 防御性拷贝（Point 可变）
        stati = List.copyOf(stati);       // 列表不可变；元素为纯值 record
    }

    /**
     * temporary stati 的纯值快照（legacy encodeTemporary 的输入侧值化）：
     * 每个 status 的数值、来源（mobSkill / skill）与反射尾所需的 x/y 都在
     * {@code Monster.view()} 时点固化，不再跨 strand 读 MonsterStatusEffect。
     */
    public record StatusSnapshot(
            MonsterStatus status,
            short value,
            boolean fromMobSkill,
            short mobSkillType,
            short mobSkillLevel,
            int skillId,
            int mobSkillX,
            int mobSkillY) {
    }
}
