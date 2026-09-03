package org.gms.remote;

/**
 * 版本无关的远端客户端对象（隔离层门面）：游戏逻辑按真实语义调用它。
 * 每连接一个实例，经 {@code Client.getRemote()} 取用；{@code Character.remote()} 提供便捷转发。
 *
 * <p>本接口只见语义模块分组，不见具体方法——防上帝接口；各域的全部语义调用
 * 归属 {@link StatsModule}/{@link SkillsModule}/{@link BasicModule}/
 * {@link CooldownModule}/{@link InventoryModule}/{@link PetModule}。
 * 设计原则（两层/事务/多对多映射）见 gms-server/doc/package-client.md。
 */
public interface RemoteClient {
    /** 开启合并域：try-with-resources 使用，close 即统一发送。嵌套开启返回空收口语义（外层负责）。 */
    RemoteUpdate update();

    StatsModule stats();

    SkillsModule skills();

    BasicModule basic();

    CooldownModule cooldown();

    InventoryModule inventory();

    PetModule pet();

    /** 无连接/已断开时的空实现——对齐 Character.sendPacket 对 client==null 的静默容忍。 */
    RemoteClient DISCONNECTED = DisconnectedClient.INSTANCE;
}
