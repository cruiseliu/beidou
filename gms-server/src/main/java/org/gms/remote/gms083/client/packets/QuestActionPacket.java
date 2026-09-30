package org.gms.remote.gms083.client.packets;

import org.gms.remote.gms083.utils.ByteBufReader;

import java.awt.Point;

/**
 * QUEST_ACTION 收包 codec（v83）：任务操作信封 `[action:1][questId:2]` + 按 action
 * 分支的载荷。本类只做解码——字节消费顺序与 legacy 逐位一致（含可用性门控读取）。
 *
 * <p>wire 形态（真客户端实测，work/ref/quest*.log）：action 1/4/5 = `[npc:4][x:2][y:2]`
 * （位置块真客户端恒带）；action 2 = 同上再 + `[selection:2]`（实测 -1 = 无奖励选择）；
 * action 0 = `[int 丢弃，疑似 npc][itemId:4]`；action 3 无更多载荷。位置/选择均为
 * 可用性门控读取（legacy 同款容错：短载荷客户端不发送时跳过）。位置块
 * （claimedPos）为 wire 全貌观察字段——语义侧不消费，但 action 2 的 selection 排在
 * 其后，解码必须按序消费这 4 字节。
 */
public final class QuestActionPacket {

    private QuestActionPacket() {
    }

    /** 解码产物（action 分支互斥）：字段即 wire 全貌，语义分流归 translator。 */
    public sealed interface Action permits RestoreLostItem, Start, Complete, Forfeit, ScriptedStart, ScriptedEnd, Unknown {
    }

    /** action=0 找回丢失物品；`unknownNpc` = legacy 丢弃不解的 int（疑似 npc id，原样解出供观察）。 */
    public record RestoreLostItem(int action, int questId, int unknownNpc, int itemId) implements Action {
    }

    /** action=1 接取任务。 */
    public record Start(int action, int questId, int npc, Point claimedPos) implements Action {
    }

    /** action=2 完成任务；`selection` = 尾部奖励选择（未携带为 null）。 */
    public record Complete(int action, int questId, int npc, Point claimedPos, Integer selection) implements Action {
    }

    /** action=3 放弃任务。 */
    public record Forfeit(int action, int questId) implements Action {
    }

    /** action=4 脚本化接取。 */
    public record ScriptedStart(int action, int questId, int npc, Point claimedPos) implements Action {
    }

    /** action=5 脚本化完成。 */
    public record ScriptedEnd(int action, int questId, int npc, Point claimedPos) implements Action {
    }

    /** 未知 action 字节：原样解出供日志观察（translator 返回 null，零语义事件）。 */
    public record Unknown(int action, int questId) implements Action {
    }

    public static Action decode(ByteBufReader p) {
        int action = p.readByte();
        int questId = p.readShort();
        return switch (action) {
            case 0 -> {
                int unknownNpc = p.readInt();
                yield new RestoreLostItem(action, questId, unknownNpc, p.readInt());
            }
            case 1 -> new Start(action, questId, p.readInt(), readClaimedPos(p));
            case 2 -> {
                int npc = p.readInt();
                Point claimedPos = readClaimedPos(p);
                Integer selection = p.available() >= 2 ? (int) p.readShort() : null;
                yield new Complete(action, questId, npc, claimedPos, selection);
            }
            case 3 -> new Forfeit(action, questId);
            case 4 -> new ScriptedStart(action, questId, p.readInt(), readClaimedPos(p));
            case 5 -> new ScriptedEnd(action, questId, p.readInt(), readClaimedPos(p));
            default -> new Unknown(action, questId);
        };
    }

    /** 声明位置块（可用性门控）：剩 ≥4 字节才读，与 legacy isNpcNearby 的容错一致。 */
    private static Point readClaimedPos(ByteBufReader p) {
        return p.available() >= 4 ? new Point(p.readShort(), p.readShort()) : null;
    }
}
