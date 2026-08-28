package org.gms.remote;

/**
 * 版本无关的远端客户端对象（隔离层门面）：游戏逻辑按真实语义调用它，
 * 当前客户端版本接受的封包格式收在实现内（见 org.gms.remote.v83）。
 * 每连接一个实例，经 {@code Client.getRemote()} 取用；{@code Character.remote()} 提供便捷转发。
 *
 * <p>本接口只见语义模块分组，不见具体方法——防上帝接口；各域的全部语义调用
 * 归属 {@link StatsModule}/{@link SkillsModule}/{@link BasicModule}/
 * {@link CooldownModule}/{@link InventoryModule}。
 *
 * <p>内置有状态合并域（transaction）：未开域时语义调用立即编码发送；
 * 开域（{@link #update()}）期间入对应域缓冲，最外层关闭时按实现声明的固定序统一组包发送。
 * wire 上怎么合并是版本编码器的私事（多对多，见 doc/09 §5.2）——语义 API 不暴露合并组。
 *
 * <p>将对称地处理收、发双向：本期仅实现 S→C（发包），C→S 预留。
 */
public interface RemoteClient {
    /** 开启合并域：try-with-resources 使用，close 即统一发送。嵌套开启返回空收口语义（外层负责）。 */
    RemoteUpdate update();

    StatsModule stats();

    SkillsModule skills();

    BasicModule basic();

    CooldownModule cooldown();

    InventoryModule inventory();

    /** 无连接/已断开时的空实现——对齐 Character.sendPacket 对 client==null 的静默容忍。 */
    RemoteClient DISCONNECTED = DisconnectedClient.INSTANCE;
}
