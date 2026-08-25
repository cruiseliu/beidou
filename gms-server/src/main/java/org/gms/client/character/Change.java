package org.gms.client.character;

/**
 * 属性变更意图（写事务 stats.update()...commit()/commitSilently() 的输入，一次可传多个）。
 * 目标属性用 {@link Prop} 统一编码：前 8 项对应 attrs/localAttrs 数组槽位，
 * HP/MP/AP 为快照中的标量字段。
 * <p>
 * 取代旧 StatsUpdate 的"纯 set 绝对值"模式：set 表达绝对写入、add 表达增量、
 * multiply 表达"有加有乘"中的乘法部分（NEW = OLD * multiplier，事务内读旧值；
 * 加的部分用 add，随机加成量由调用方/工具方法先行计算）、
 * recalc 不是变更意图：装备/buff 变化触发时走独立 API stats.recalc()。
 */
public sealed interface Change permits Change.Set, Change.Add, Change.Multiply {

    /** 属性目标（内部 helper：面板属性对应 StatIndex 槽位，HP/MP/AP 为临时状态/资源，slot = null） */
    enum Prop {
        STR(Stat.STR),
        DEX(Stat.DEX),
        INT(Stat.INT),
        LUK(Stat.LUK),
        MAX_HP(Stat.MAX_HP),
        MAX_MP(Stat.MAX_MP),
        P_ATK(Stat.P_ATK),
        M_ATK(Stat.M_ATK),
        P_DEF(Stat.P_DEF),
        M_DEF(Stat.M_DEF),
        ACCURACY(Stat.ACCURACY),
        AVOIDABILITY(Stat.AVOIDABILITY),
        SPEED(Stat.SPEED),
        JUMP(Stat.JUMP),
        HANDS(Stat.HANDS),
        HP(null),
        MP(null),
        AP(null);

        /** 面板属性槽位（base/total 数组下标用 slot().ordinal()）；HP/MP/AP 为 null */
        final Stat slot;

        Prop(Stat slot) {
            this.slot = slot;
        }

        boolean isAttrSlot() {
            return slot != null;
        }
    }

    /** 绝对值写入 */
    record Set(Prop prop, int value) implements Change {
    }

    /** 增量（读旧值 + delta） */
    record Add(Prop prop, int delta) implements Change {
    }

    /** 乘法：NEW = OLD * multiplier（读旧值；int 截断） */
    record Multiply(Prop prop, double multiplier) implements Change {
    }

}

