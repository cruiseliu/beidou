package org.gms.remote.gms083.server.translators;

import org.gms.client.character.ExpSource;
import org.gms.remote.gms083.server.packets.ShowStatusInfoPacket;

/**
 * 经验获得翻译：source → 显示形态。仅 QUEST（in-chat、非白字）；新增来源在此
 * 增量补显示分支（switch 穷尽性强制）。
 */
public final class ExpGainTranslator {

    public ShowStatusInfoPacket display(int gain, ExpSource source) {
        return switch (source) {
            case QUEST -> new ShowStatusInfoPacket(new ShowStatusInfoPacket.Body.ExpGain(false, gain, true));
        };
    }
}
