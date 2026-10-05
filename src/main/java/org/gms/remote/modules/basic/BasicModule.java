package org.gms.remote.modules.basic;

import org.gms.client.character.Character;
import org.gms.remote.AbstractModule;
import org.gms.remote.ServerEvent;
import org.gms.client.character.ExpSource;
import org.gms.remote.modules.basic.server.GainExpEvent;
import org.gms.remote.modules.basic.server.InitializeEvent;
import org.gms.remote.modules.basic.server.UnlockActionsEvent;
import org.gms.remote.modules.basic.server.UpdateExpEvent;
import org.gms.remote.modules.basic.server.UpdateJobEvent;
import org.gms.remote.modules.basic.server.UpdateLevelEvent;

/** 基础标识域（语义基类）：API call → ServerEvent 的转换在此，wire 归版本 deliver。 */
public abstract class BasicModule extends AbstractModule {

    /** jobId 变更（转职）。 */
    public final void updateJob(int jobId) {
        post(new UpdateJobEvent(jobId));
    }

    /** level 变更。 */
    public final void updateLevel(int level) {
        post(new UpdateLevelEvent(level));
    }

    /** exp 变更。 */
    public final void updateExp(long exp) {
        post(new UpdateExpEvent(exp));
    }

    /** 获得经验（状态应用归 gameplay；exp 数值帧 + 演出帧由 source → 版本 translator 决定）。 */
    public final void gainExp(int gain, int totalExp, ExpSource source) {
        post(new GainExpEvent(gain, totalExp, source));
    }

    public final void unlockActions() {
        post(new UnlockActionsEvent());
    }

    /**
     * Send all data. 活引用默认不完整冻结；版本经统一冻结门物化成品帧
     * （FrozenInitializeEvent，wire 事实入域时点抽取）。
     */
    public final void initialize(Character chr) {
        post(new InitializeEvent(chr));
    }
}
