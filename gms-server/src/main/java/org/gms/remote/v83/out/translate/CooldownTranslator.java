package org.gms.remote.v83.out.translate;

import org.gms.remote.v83.out.packet.CooldownPacket;
import org.gms.remote.v83.out.packet.V83Packet;

import java.util.ArrayList;
import java.util.List;

/** 冷却域：清除（time=0，到期/重置）；每个清除一个包。 */
public final class CooldownTranslator implements Translator {
    private final List<CooldownPacket> packets = new ArrayList<>();

    public void onCooldownClear(int skillId) {
        packets.add(new CooldownPacket(skillId, (short) 0));
    }

    @Override
    public boolean isEmpty() {
        return packets.isEmpty();
    }

    @Override
    public List<V83Packet> flush() {
        List<V83Packet> out = new ArrayList<>(packets);
        packets.clear();
        return out;
    }
}
