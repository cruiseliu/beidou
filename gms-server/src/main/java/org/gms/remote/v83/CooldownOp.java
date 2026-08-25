package org.gms.remote.v83;

import org.gms.client.Client;
import org.gms.net.opcodes.SendOpcode;
import org.gms.net.packet.OutPacket;
import org.gms.net.packet.Packet;

import java.util.ArrayList;
import java.util.List;

/**
 * COOLDOWN（0xEA）：技能冷却显示（自 PacketCreator.skillCooldown 逐字节移植）。
 * 当前语义面只有"清除"（time=0，到期/重置）；每个清除一个包。
 */
final class CooldownOp implements V83Op {
    private final List<Integer> clears = new ArrayList<>();

    void mergeClear(int skillId) {
        clears.add(skillId);
    }

    @Override
    public boolean isEmpty() {
        return clears.isEmpty();
    }

    @Override
    public void sendTo(Client client) {
        for (int skillId : clears) {
            client.sendPacket(encode(skillId, 0));
        }
        clears.clear();
    }

    private Packet encode(int skillId, int time) {
        OutPacket p = OutPacket.create(SendOpcode.COOLDOWN);
        p.writeInt(skillId);
        p.writeShort(time);
        return p;
    }
}
