package org.gms.client.character;

import org.gms.server.BuffEffectData;

import java.util.ArrayList;
import java.util.List;

/** 同一 buff 源的完整在册状态：源 id + 效果配置 + 生效时刻 + 持续时长 + 该源各槽位的效果项。
 *  作为 CharacterBuffs.entries 的值类型（key 即 sourceId）。
 *  data/startTime/duration 为该源所有槽位共享（一个 buff 一个配置、一个生效时刻、一个持续时长），
 *  到期时刻不再单独存储，需要时按 startTime + duration 推导；
 *  EffectStatus 仅持槽位类型/值与对本对象的反向引用。 */
class BuffStatus {
    /** buff 源 id（getBuffSourceId 约定：技能为正 id，道具为负 id） */
    public final int sourceId;
    /** 效果配置（同源各槽位共享同一实例） */
    public final BuffEffectData data;
    /** 生效时刻（绝对时间戳）；随 freeze/resume 一并推移 */
    public long startTime;
    /** 持续时长（毫秒）；到期时刻 = startTime + duration，随 startTime 推移自动成立 */
    public long duration;
    /** 该源各槽位的效果项 */
    public final List<EffectStatus> effects = new ArrayList<>();

    public BuffStatus(int sourceId, BuffEffectData data, long startTime, long duration) {
        this.sourceId = sourceId;
        this.data = data;
        this.startTime = startTime;
        this.duration = duration;
    }
}
