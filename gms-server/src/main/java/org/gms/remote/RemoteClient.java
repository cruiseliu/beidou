package org.gms.remote;

/**
 * 版本无关的远端客户端对象（隔离层门面）：游戏逻辑按真实语义调用它，
 * 当前客户端版本接受的封包格式收在实现内（见 org.gms.remote.v83）。
 * 每连接一个实例，经 {@code Client.getRemote()} 取用；{@code Character.remote()} 提供便捷转发。
 *
 * <p>将对称地处理收、发双向：本期仅实现 S→C（发包），C→S（收包解析为语义事件）预留。
 */
public interface RemoteClient {
    /** 开启一次语义事务（合并边界 = 游戏逻辑的一个完整语义单元）。 */
    RemoteUpdate update();

    /** 无连接/已断开时的空实现——对齐 Character.sendPacket 对 client==null 的静默容忍。 */
    RemoteClient DISCONNECTED = new RemoteClient() {
        @Override
        public RemoteUpdate update() {
            return new RemoteUpdate() {
                @Override
                public RemoteUpdate updateStats(StatsUpdate update) {
                    return this;
                }

                @Override
                public RemoteUpdate updateSp(SpUpdate update) {
                    return this;
                }

                @Override
                public RemoteUpdate unlockActions() {
                    return this;
                }

                @Override
                public void commit() {
                }

                @Override
                public void close() {
                }
            };
        }
    };
}
