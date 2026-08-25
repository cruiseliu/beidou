package org.gms.remote;

/**
 * 版本无关的远端客户端对象（隔离层门面）：游戏逻辑按真实语义调用它，
 * 当前客户端版本接受的封包格式收在实现内（见 org.gms.remote.v83）。
 * 每连接一个实例，经 {@code Client.getRemote()} 取用；{@code Character.remote()} 提供便捷转发。
 *
 * <p>内置有状态合并域（transaction）：未开域时语义调用立即编码发送；
 * 开域（{@link #update()}）期间所有语义调用入队（同字段后写覆盖，绝对值合并即净 diff），
 * 域关闭时统一组包发送。组件无需感知合并——一律正常公告，合并范围由编排层
 * （一个 handler / 一次升级 / 一次转职）开域决定。
 *
 * <p>将对称地处理收、发双向：本期仅实现 S→C（发包），C→S（收包解析为语义事件）预留。
 */
public interface RemoteClient {
    /** 开启合并域：try-with-resources 使用，close 即统一发送。嵌套开启返回空句柄（外层收口）。 */
    RemoteUpdate update();

    /** 面板属性 + hp/mp/ap 通知（stats 域）。无打开的合并域时立即发送。 */
    void updateStats(StatsUpdate update);

    /** SP 通知（技能域，按职业分桶的原始事实）。无打开的合并域时立即发送。 */
    void updateSp(SpUpdate update);

    /** 基础标识通知（basic 域：jobId/level/exp）。无打开的合并域时立即发送。 */
    void updateBasic(BasicUpdate update);

    /** 解除客户端动作锁（v83 中并入 STAT_CHANGED 首字节；无其他内容时=空更新包）。 */
    void unlockActions();

    /** 无连接/已断开时的空实现——对齐 Character.sendPacket 对 client==null 的静默容忍。 */
    RemoteClient DISCONNECTED = new RemoteClient() {
        @Override
        public RemoteUpdate update() {
            return RemoteUpdate.NOOP;
        }

        @Override
        public void updateStats(StatsUpdate update) {
        }

        @Override
        public void updateSp(SpUpdate update) {
        }

        @Override
        public void updateBasic(BasicUpdate update) {
        }

        @Override
        public void unlockActions() {
        }
    };
}
