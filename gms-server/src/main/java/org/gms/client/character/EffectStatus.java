package org.gms.client.character;

import org.gms.client.EffectType;
import org.gms.server.BuffEffectData;

/** buff 槽位效果值：槽位类型 + 槽位值 + 所属 buff 的反向引用。
 *  效果配置与生效时刻已上移到 BuffStatus（同源各槽位共享一份），经 getData/getStartTime 取用。 */
class EffectStatus {
    public final EffectType type;
    /** 所属 buff（提供 data/startTime 的溯源） */
    public final BuffStatus buff;
    /** 槽位值 */
    public int value;

    public EffectStatus(EffectType type, BuffStatus buff, int value) {
        this.type = type;
        this.buff = buff;
        this.value = value;
    }

    public BuffEffectData getData() {
        return buff.data;
    }
}
