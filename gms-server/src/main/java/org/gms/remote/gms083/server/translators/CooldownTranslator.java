package org.gms.remote.gms083.server.translators;

import java.util.ArrayList;
import java.util.List;

import org.gms.remote.gms083.ServerTranslator;
import org.gms.remote.gms083.server.packets.CooldownPacket;
import org.gms.remote.gms083.server.packets.V83Packet;

/** 冷却域：清除（time=0，到期/重置）；每个清除一个包。 */
public final class CooldownTranslator implements ServerTranslator {
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
