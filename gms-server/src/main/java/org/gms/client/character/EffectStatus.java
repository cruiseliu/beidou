package org.gms.client.character;

import org.gms.client.EffectType;
import org.gms.server.EffectData;

/** buff 槽位效果值：槽位类型 + effect + 生效时刻 + 槽位值。
 *  自描述（携带所属槽位），簿记容器用 List 而非按槽位键控的 Map。 */
class EffectStatus {
    public final EffectType type;
    public EffectData data;
    public long startTime;
    public int value;

    public EffectStatus(EffectType type, EffectData statEffect, long startTime, int value) {
        this.type = type;
        this.data = statEffect;
        this.startTime = startTime;
        this.value = value;
    }
}
