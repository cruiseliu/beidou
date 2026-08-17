package org.gms.client.character;

import org.gms.server.StatEffect;

/** buff 槽位效果值：effect + 生效时刻 + 槽位值 + bestApplied（被取消时是否需要重选最佳） */
class BuffStatValueHolder {
    public StatEffect effect;
    public long startTime;
    public int value;
    public boolean bestApplied;

    public BuffStatValueHolder(StatEffect effect, long startTime, int value) {
        this.effect = effect;
        this.startTime = startTime;
        this.value = value;
        this.bestApplied = false;
    }
}
