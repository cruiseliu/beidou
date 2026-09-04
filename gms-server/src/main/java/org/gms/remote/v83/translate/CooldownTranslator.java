package org.gms.remote.v83.translate;

import io.netty.buffer.ByteBuf;
import org.gms.remote.v83.PacketRecordLog;
import org.gms.remote.v83.packet.CooldownPacket;



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
    public List<ByteBuf> flush() {
        List<ByteBuf> frames = new ArrayList<>(packets.size());
        for (CooldownPacket packet : packets) {
            PacketRecordLog.debug(packet);
            frames.add(CooldownPacket.encode(packet));
        }
        packets.clear();
        return frames;
    }
}
