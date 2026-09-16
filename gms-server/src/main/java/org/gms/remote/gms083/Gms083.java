package org.gms.remote.gms083;

import com.alibaba.fastjson2.JSON;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import org.gms.client.Client;
import org.gms.constants.string.CharsetConstants;
import org.gms.net.PacketHandler;
import org.gms.net.PacketProcessor;
import org.gms.net.packet.InPacket;
import org.gms.net.opcodes.RecvOpcode;
import org.gms.net.packet.Packet;
import org.gms.client.inventory.Equip;
import org.gms.client.inventory.EquipFlag;
import org.gms.client.inventory.InventoryType;
import org.gms.client.inventory.Item;
import org.gms.client.inventory.ItemFlag;
import org.gms.remote.modules.basic.BasicModule;
import org.gms.remote.modules.inventory.InventoryModule;
import org.gms.remote.modules.map.client.MapModule;
import org.gms.remote.modules.message.MessageModule;
import org.gms.remote.modules.npc.client.NpcModule;
import org.gms.remote.modules.pet.PetModule;
import org.gms.remote.RemoteClient;
import org.gms.remote.RemoteClientBase;
import org.gms.remote.gms083.server.packets.V83Packet;
import org.gms.remote.modules.skills.SkillsModule;
import org.gms.remote.modules.stats.StatsModule;
import org.gms.util.HexTool;
import org.gms.util.ThreadLocalUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.Charset;

/**
 * gms083 版本门面：模块出脸 + freeze/deliver/flush 全部下沉在各域 route
 * （org.gms.remote.gms083.out.route，BatchSink/Wire 经构造注入）；本类承载
 * 跨模块的版本机器：合并域状态机（基类机器，本类实现 BatchSink）、固定冲刷序
 * （stats → skills → cooldown → inventory，业务序显式书写）、wire/传输适配。
 * 本类不出现任何具体事件类型。分层与原则见 gms-server/doc/package-client.md。
 */
public final class Gms083 extends RemoteClientBase implements RemoteClient {

    private static final Logger log = LoggerFactory.getLogger(Gms083.class);

    /** v83 客户端旗标字的逐位拼装——legacy 旗标视图（Item.getLegacyFlags）的唯一组装点。 */
    public static int assembleClientFlagBits(Item item) {
        int bits = 0;
        boolean equipType = item.getInventoryTab() == InventoryType.EQUIP;
        for (ItemFlag f : ItemFlag.values()) {
            if (!item.hasFlag(f)) {
                continue;
            }
            if (f == ItemFlag.SCISSOR_USABLE) {
                continue;   // 服务端语义标签，客户端无此位
            }
            if (f == ItemFlag.TRADE_ONCE) {
                bits |= equipType ? ItemFlag.LEGACY_KARMA_EQP
                                  : ItemFlag.LEGACY_KARMA_USE;
                continue;
            }
            bits |= f.legacyValue();
        }
        Equip equipInfo = item.getEquipInfo();
        if (equipInfo != null) {
            for (EquipFlag f : EquipFlag.values()) {
                if (equipInfo.hasFlag(f)) {
                    bits |= f.legacyValue();
                }
            }
        }
        return bits;
    }

    private final Client client;
    private final PacketProcessor processor;
    private Gms083Routers routers;
    private Gms083Translators translators;

    public Gms083(Client client, PacketProcessor processor) {
        this.client = client;
        this.processor = processor;

        Charset charset = CharsetConstants.getCharset(ThreadLocalUtil.getClientLang());
        translators = new Gms083Translators(charset);

        routers = new Gms083Routers(this);
    }

    /** 世界域 C→S 分派（opcode → 世界 handler 表；表外 op 返回 null 由管道丢弃）。
     * PLAYER_LOGGEDIN 特判：连接初始化协议不走 handler 表（doc/12 §21），分发到
     * 基类 final 模板 clientInit（queued 裸 strand，会话建立/换绑 + 入场编舞）。 */
    @Override
    public PacketHandler resolveHandler(short opcode) {
        if (opcode == RecvOpcode.PLAYER_LOGGEDIN.getValue()) {
            return new PacketHandler() {
                @Override
                public boolean validateState(Client c) {
                    return !c.isLoggedIn();   // 原 PlayerLoggedinHandler.validateState
                }

                @Override
                public boolean queued() {
                    return true;   // 裸 strand：协议前半含 loadCharFromDB（半加载态语义）
                }

                @Override
                public void handlePacket(InPacket p, Client c) {
                    clientInit(p.readInt(), c);
                }
            };
        }
        return processor.getHandler(opcode);
    }

    public Gms083Translators translators() {
        return translators;
    }

    public Client getLegacyClient() {
        return client;
    }

    // ── Wire：encode + 直发 ──

    public Packet toLegacyPacket(V83Packet packet) {
        if (log.isDebugEnabled()) {
            // record 携带实体引用时 JSON 序列化可能撞对象图循环（fastjson2 层级上限，
            // 循环可致 StackOverflowError）——诊断日志降级，发包不受影响；hex trace 恒可用
            String json;
            try {
                json = JSON.toJSONString(packet);
            } catch (Throwable t) {
                // StackOverflowError 也是合法结果（实体对象图循环，如 GuildCharacter）——
                // 诊断日志不得有任何抛出路径
                json = "<unserializable>";
            }
            log.debug("[remote] {} {}", packet.opcode(), json);
        }
        ByteBuf frame = packet.encode();
        if (log.isTraceEnabled()) {
            log.trace("[remote] {} hex {}", packet.opcode(), HexTool.toHexString(ByteBufUtil.getBytes(frame)));
        }
        return new BytesPacket(frame);
    }

    public void send(V83Packet packet) {
        client.sendPacket(toLegacyPacket(packet));
    }

    /** 传输适配：帧 ByteBuf → Packet（复用既有加密/发送管线） */
    private record BytesPacket(ByteBuf frame) implements Packet {
        @Override
        public byte[] getBytes() {
            return ByteBufUtil.getBytes(frame);
        }
    }

    // ── RemoteClient 钩子 ──

    /** 固定冲刷序：stats → skills（含冷却包，原 cooldown 段并入 skills 冲刷）→ inventory（业务序，版本显式书写） */
    @Override
    protected void flushAll() {
        routers.stats.flush();
        routers.skills.flush();
        routers.inventory.flush();
    }

    // ── 模块访问器 ──

    @Override public StatsModule stats() { return routers.stats; }

    @Override public SkillsModule skills() { return routers.skills; }

    @Override public BasicModule basic() { return routers.basic; }

    @Override public InventoryModule inventory() { return routers.inventory; }

    @Override public PetModule pet() { return routers.pet; }

    @Override public MapModule map() { return routers.map; }

    @Override public NpcModule npc() { return routers.npc; }

    @Override public MessageModule message() { return routers.message; }
}
