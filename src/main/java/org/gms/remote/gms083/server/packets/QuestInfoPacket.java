package org.gms.remote.gms083.server.packets;

import io.netty.buffer.ByteBuf;
import org.gms.net.opcodes.SendOpcode;
import org.gms.remote.gms083.utils.ByteBufBuilder;

/**
 * UPDATE_QUEST_INFO（按 opcode 一 record、嵌套 sealed body，首字节为分支类型位）：
 * <ul>
 *   <li>{@link Body.NpcDelivery}（type 8）：任务在指定 NPC 处可交付（系列终结形态，尾 int 0）。</li>
 *   <li>{@link Body.SeriesContinue}（type 8）：任务链续环引导——complete 后 WZ 声明的下一环
 *       任务 id（尾 short nextQuest，与 legacy updateQuestFinish 同宽同序）。</li>
 *   <li>{@link Body.TimeLimitAdded}（type 6）：限时追加（size 位恒 1 单条；wire 时间 =
 *       距到期剩余 ms 截断 int，与 legacy 同宽）。</li>
 *   <li>{@link Body.TimeLimitRemoved}（type 7）：限时移除（position 位恒 1）。</li>
 *   <li>{@link Body.Expired}（type 0x0F）：任务到期作废。</li>
 * </ul>
 */
public record QuestInfoPacket(Body body) implements V83Packet {

    public sealed interface Body {
        record NpcDelivery(int questId, int npc) implements Body {
        }

        record SeriesContinue(int questId, int npc, int nextQuest) implements Body {
        }

        record TimeLimitAdded(int questId, long remainingMillis) implements Body {
        }

        record TimeLimitRemoved(int questId) implements Body {
        }

        record Expired(int questId) implements Body {
        }
    }

    @Override
    public SendOpcode opcode() {
        return SendOpcode.UPDATE_QUEST_INFO;
    }

    @Override
    public ByteBuf encode() {
        ByteBufBuilder p = new ByteBufBuilder();
        p.writeShort(opcode().getValue());
        switch (body) {
            case Body.NpcDelivery(int questId, int npc) -> {
                p.writeByte(8); //0x0A in v95
                p.writeShort(questId);
                p.writeInt(npc);
                p.writeInt(0);
            }
            case Body.SeriesContinue(int questId, int npc, int nextQuest) -> {
                p.writeByte(8); //0x0A in v95
                p.writeShort(questId);
                p.writeInt(npc);
                p.writeShort(nextQuest);
            }
            case Body.TimeLimitAdded(int questId, long remainingMillis) -> {
                p.writeByte(6);
                p.writeShort(1);//Size but meh, when will there be 2 at the same time? And it won't even replace the old one :)
                p.writeShort(questId);
                p.writeInt((int) remainingMillis);
            }
            case Body.TimeLimitRemoved(int questId) -> {
                p.writeByte(7);
                p.writeShort(1);//Position
                p.writeShort(questId);
            }
            case Body.Expired(int questId) -> {
                p.writeByte(0x0F);
                p.writeShort(questId);
            }
        }
        return p.build();
    }
}
