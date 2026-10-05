package org.gms.remote.gms083.server.translators;

import org.gms.remote.gms083.server.packets.QuestInfoPacket;
import org.gms.remote.gms083.server.packets.QuestStatusPacket;

import java.util.Map;

/**
 * 任务域翻译（无状态，无合并域——每事件直出包 record，flush 恒空归 router）：
 * gameplay 事实（questId/status/进度表/时间/npc/nextQuest）→ 包 record；
 * 进度 wire 规范化（{@link QuestProgressFormat#toWire}）在本层完成，packet 字段不再换算。
 * 实体档事件（接取/完成/放弃全量帧）不走本类——帧物化归 router 统一冻结门。
 */
public final class QuestTranslator {

    /** 任务状态/进度帧（status = QuestStatus 枚举值；infoNumber 关联任务同步同型） */
    public QuestStatusPacket questState(int questId, int status, Map<Integer, String> progress) {
        return new QuestStatusPacket(new QuestStatusPacket.Body.Update(
                questId, status, QuestProgressFormat.toWire(progress)));
    }

    /** 任务系列终结（任务链无下一环；type 8 交付分支，尾 int 0） */
    public QuestInfoPacket seriesComplete(int questId, int npc) {
        return new QuestInfoPacket(new QuestInfoPacket.Body.NpcDelivery(questId, npc));
    }

    /** 任务链续环引导（complete 后 WZ 声明的下一环；type 8 续环分支，尾 short nextQuest） */
    public QuestInfoPacket seriesContinue(int questId, int npc, int nextQuest) {
        return new QuestInfoPacket(new QuestInfoPacket.Body.SeriesContinue(questId, npc, nextQuest));
    }

    /** 限时追加（wire 时间 = 距到期剩余 ms 截断 int） */
    public QuestInfoPacket timeLimitAdded(int questId, long remainingMillis) {
        return new QuestInfoPacket(new QuestInfoPacket.Body.TimeLimitAdded(questId, remainingMillis));
    }

    /** 限时移除 */
    public QuestInfoPacket timeLimitRemoved(int questId) {
        return new QuestInfoPacket(new QuestInfoPacket.Body.TimeLimitRemoved(questId));
    }

    /** 任务到期作废 */
    public QuestInfoPacket expired(int questId) {
        return new QuestInfoPacket(new QuestInfoPacket.Body.Expired(questId));
    }
}
