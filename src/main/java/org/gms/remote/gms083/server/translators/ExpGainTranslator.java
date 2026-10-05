package org.gms.remote.gms083.server.translators;

import org.gms.remote.gms083.server.packets.ExpGainPacket;
import org.gms.remote.modules.basic.server.ExpSource;

/**
 * 经验获得翻译：source → 显示形态。仅 QUEST（in-chat、非白字）；新增来源在此
 * 增量补显示分支（switch 穷尽性强制）。
 */
public final class ExpGainTranslator {

    public ExpGainPacket display(int gain, ExpSource source) {
        return switch (source) {
            case QUEST -> new ExpGainPacket(false, gain, true);
        };
    }
}
