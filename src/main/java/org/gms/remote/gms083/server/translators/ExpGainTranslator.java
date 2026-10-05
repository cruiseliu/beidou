package org.gms.remote.gms083.server.translators;

import org.gms.client.character.ExpSource;
import org.gms.remote.gms083.server.packets.ShowStatusInfoPacket;
import org.gms.remote.gms083.server.packets.StatChangedPacket;
import org.gms.remote.gms083.server.packets.V83Packet;

import java.util.List;

/**
 * 经验获得翻译：一次语义获得 → 双包（序同 legacy updateSingleStat → announceExpGain）：
 * <ol>
 *   <li>STAT_CHANGED exp 数值帧（unlock=false——对话框期间不解锁的 legacy 语义刻意保留，
 *       与 StatsTranslator 的 auto-unlock 差异为字节等价取舍）。</li>
 *   <li>SHOW_STATUS_INFO 演出帧，显示形态由 source 决定（仅 QUEST：in-chat、非白字；
 *       新增来源在此增量补分支，switch 穷尽性强制）。</li>
 * </ol>
 */
public final class ExpGainTranslator {

    private static final int MASK_EXP = 0x10000;

    public List<V83Packet> display(int gain, int totalExp, ExpSource source) {
        ShowStatusInfoPacket show =
                new ShowStatusInfoPacket(new ShowStatusInfoPacket.Body.ExpGain(false, gain, true));
        return switch (source) {
            case QUEST -> List.of(
                    StatChangedPacket.of(false,
                            List.of(new StatChangedPacket.StatEntry(MASK_EXP, totalExp)), null),
                    show);
        };
    }
}
