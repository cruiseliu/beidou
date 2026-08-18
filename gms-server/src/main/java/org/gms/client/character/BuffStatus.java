package org.gms.client.character;

import java.util.List;

/** 同一 buff 源的完整在册状态：源 id + 该源各槽位的效果项。
 *  作为 CharacterBuffs.buffEffects 的值类型（key 即 sourceId），遍历时无需回查 map entry 的 key。 */
class BuffStatus {
    /** buff 源 id（getBuffSourceId 约定：技能为正 id，道具为负 id） */
    public final int sourceId;
    /** 到期时刻（绝对时间戳）；随 freeze/resume 一并推移 */
    public long expire;
    public final List<EffectStatus> effects;

    public BuffStatus(int sourceId, long expire, List<EffectStatus> effects) {
        this.sourceId = sourceId;
        this.expire = expire;
        this.effects = effects;
    }
}
