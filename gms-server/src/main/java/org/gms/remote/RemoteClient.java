package org.gms.remote;

import org.gms.remote.modules.basic.BasicModule;
import org.gms.remote.modules.cooldown.CooldownModule;
import org.gms.remote.modules.inventory.InventoryModule;
import org.gms.remote.modules.map.client.MapModule;
import org.gms.remote.modules.npc.client.NpcModule;
import org.gms.remote.modules.pet.PetModule;
import org.gms.remote.modules.skills.SkillsModule;
import org.gms.remote.modules.stats.StatsModule;

/**
 * 世界域（player strand）允许使用的远端客户端视图：语义模块分组，防上帝接口——
 * 各域的全部语义调用归属各 Module。实现 = {@code Gms083}（每连接一个，经
 * {@code Player.remote()} strand 抽象获取；Client 不在公共面暴露语义层概念）。
 * 跳板域视图 = LoginRemoteClient（doc/12 相 C 演进）。
 */
public interface RemoteClient {

    BasicModule basic();

    StatsModule stats();

    SkillsModule skills();

    CooldownModule cooldown();

    InventoryModule inventory();

    PetModule pet();

    MapModule map();

    NpcModule npc();

    /**
     * 开启合并域：try-with-resources 使用，close 即统一发送（机器在基类；世界域
     * 语义调用，入 interface 便于瀑布式书写）。
     */
    RemoteClientBatch batch();
}
