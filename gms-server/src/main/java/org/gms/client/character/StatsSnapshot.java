package org.gms.client.character;

import static org.gms.client.character.Stat.count;

/**
 * 角色属性状态快照（不可变）。attrs/localAttrs 数组语义上不可改：
 * Java 无不可变数组，由 caller guarantee 保证——写路径 copy-on-write（新建数组替换引用），
 * 一经发布进本快照即不再修改；未变化的数组按引用复用（见 CharacterStats.applyChanges）。
 * 读端无锁访问 volatile 发布点，永远看到某个已完成事务的一致视图。
 */
record StatsSnapshot(int[] base, int[] total, int hp, int mp, int ap) {

    /** 初始快照：数组全 0，local 的 HP/MP 上限给 50/5 初始值（与历史字段默认一致） */
    static StatsSnapshot initial() {
        int[] base = new int[count()];
        int[] total = new int[count()];
        total[Stat.MAX_HP.ordinal()] = 50;
        total[Stat.MAX_MP.ordinal()] = 5;
        return new StatsSnapshot(base, total, 0, 0, 0);
    }
}
