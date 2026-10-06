package org.gms.remote.gms083.client;

import org.gms.client.Client;
import org.gms.client.Player;
import org.gms.client.character.Character;
import org.gms.constants.string.CharsetConstants;
import org.gms.net.AbstractPacketHandler;
import org.gms.net.opcodes.RecvOpcode;
import org.gms.net.packet.InPacket;
import org.gms.remote.gms083.client.routers.AbstractInRouter;
import org.gms.remote.gms083.client.routers.BattleInRouter;
import org.gms.remote.gms083.client.routers.InventoryInRouter;
import org.gms.remote.gms083.client.routers.MapInRouter;
import org.gms.remote.gms083.client.routers.NpcInRouter;
import org.gms.remote.gms083.client.routers.PetInRouter;
import org.gms.remote.gms083.client.routers.QuestInRouter;
import org.gms.remote.gms083.utils.ByteBufReader;
import org.gms.util.ThreadLocalUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * in-op shim（收包管线唯一入口）：net 层按 opcode 注册本类实例（handlePacket 不携带
 * opcode，构造期持有自己的 {@link RecvOpcode}），fan-out 给全部 in-route——route 单开关
 * 自报是否接收，0 接收 = 装配不一致，log error 后丢弃（合法但未迁移的 opcode 走 legacy
 * processor，不会到达此处）。
 *
 * <p><b>约束</b>：decode → dispatch 全程在 player strand 上执行（本方法即 strand 任务）；
 * 管线内异常由 strand fail-safe 吞掉记日志（与旧 dispatch 模板同级容错）。InPacket 只在
 * 本类转成 {@link ByteBufReader}（net 层类型不越过 shim）。
 */
public final class Gms083ShimHandler extends AbstractPacketHandler {

    private static final Logger log = LoggerFactory.getLogger(Gms083ShimHandler.class);

    /** in-route 装配（版本内自声明；各 router 的 case 集合互斥） */
    private static final List<AbstractInRouter> ROUTERS = List.of(
            new MapInRouter(), new NpcInRouter(), new PetInRouter(), new InventoryInRouter(), new QuestInRouter(),
            new BattleInRouter());

    private final RecvOpcode opcode;

    /** 每个已迁移 opcode 一个实例（共享静态 ROUTERS） */
    public Gms083ShimHandler(RecvOpcode opcode) {
        this.opcode = opcode;
    }

    @Override
    public boolean queued() {
        return true;   // strand 迁移：in-op 收包管线（decode→dispatch 单 strand）
    }

    @Override
    public void handlePacket(InPacket p, Client c) {
        c.getStrand().run("in-" + opcode.name(), () -> {
            Player player = Player.require("in " + opcode.name());
            Character chr = player.character();
            if (chr == null) {   // 转换窗口守卫（validateState 后角色理论上已挂）
                return;
            }
            // 字符集按会话语言注入（读侧构造期固定；与原 ByteBufInPacket.readString 的
            // ThreadLocal 读取同时机、同值）
            ByteBufReader in = new ByteBufReader(p.getBytes(),
                    CharsetConstants.getCharset(ThreadLocalUtil.getClientLang()));
            int accepted = 0;
            for (AbstractInRouter router : ROUTERS) {
                if (router.route(opcode, in, player)) {
                    accepted++;
                }
            }
            if (accepted == 0) {
                log.error("opcode {} 到达 shim 但无 router 接收（装配不一致），丢弃", opcode);
            }
        });
    }
}
