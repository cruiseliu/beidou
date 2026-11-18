package org.gms.remote.gms083.client.packets;

import org.gms.remote.gms083.client.blocks.ObjectMoveBlock;
import org.gms.remote.gms083.utils.ByteBufReader;
import org.gms.util.AssertUtil;

/**
 * NPC_ACTION 收包 codec（v83）：客户端动画状态机（doc/13）。本类只做解码——按信封长度
 * 三分支把全部字节解成具名字段（含<b>不回发</b>的尾部字节）；字段语义未解释，命名
 * unknownN 留待日志观察猜值。数据的用途（回声/消费）不归本类。
 *
 * <p>wire 信封（服务端已知全貌）：available==6 → "talk"（int + byte + byte）；
 * available&gt;6 → move（前 length-9 字节内容 + 尾部 9 字节，尾部服务端不消费不回发，
 * 现状怪癖原样保留）；available&lt;6 → 无可解数据（空包）。
 *
 * <p>FIXME(NPC_ACTION 广播)：官方服务器任务完成时 NPC 会对全图做特定表情（NPC_ACTION 的
 * 广播形态），私服缺失该广播导致表现缺失。与本包解码无关——待"服务端消费 NPC 动画/表情"
 * 的需求成立时（语义化逆向 + 广播拓扑）一并处理。
 */
public final class NpcActionClientPacket {

    private NpcActionClientPacket() {
    }

    /** 解码产物（三分支互斥）：字段即 wire 全貌，回发裁剪归 translator。 */
    public sealed interface Action permits Talk, Move {
    }

    /** "talk"（==6）：全部字节均回发。 */
    public record Talk(
        int oid,
        int action,
        int frame
    ) implements Action {}

    /**
     * "move"（&gt;6）：content = 回发内容（前 length-9 字节）；unknown4~12 = 尾部 9 字节
     * （服务端不消费、不回发——现状逻辑，逐字节解出供观察）。
     */
    public record Move(
        int oid,
        int action,
        int frame,
        int x,
        int y,
        ObjectMoveBlock move,
        int keyLength,
        int rectLeft,
        int rectTop,
        int rectRight,
        int rectBottom
    ) implements Action {}

    // /** 短包（&lt;6）：无可解数据，回发空载荷（现状怪癖，原样保留）。 */
    // public record Empty() implements Action {
    // }

    public static Action decode(ByteBufReader p) {
        int length = p.available();
        if (length == 6) { // "talk"
            return new Talk(p.readInt(), p.readByte(), p.readByte());
        } else if (length > 6) { // "move"
            int oid = p.readInt();
            int action = p.readByte();
            int frame = p.readByte();
            int x = p.readShort();
            int y = p.readShort();
            ObjectMoveBlock move = ObjectMoveBlock.decode(p);

            return new Move(
                oid,
                action,
                frame,
                x,
                y,
                move,
                p.readByte(),
                p.readShort(),
                p.readShort(),
                p.readShort(),
                p.readShort()
            );
        }

        throw AssertUtil.never();
        // return new Empty();
    }
}
