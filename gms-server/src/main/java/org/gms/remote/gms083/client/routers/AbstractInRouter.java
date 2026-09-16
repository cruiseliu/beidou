package org.gms.remote.gms083.client.routers;

import com.alibaba.fastjson2.JSON;
import org.gms.client.Player;
import org.gms.client.character.Character;
import org.gms.net.opcodes.RecvOpcode;
import org.gms.remote.ClientEvent;
import org.gms.remote.ClientEventDispatcher;
import org.gms.remote.gms083.client.translate.InCodec;
import org.gms.remote.gms083.client.translate.InTranslator;
import org.gms.remote.gms083.utils.ByteBufReader;
import org.gms.util.HexTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

/**
 * in-route 基类：只承载收包编排管道（对偶 S→C 的 AbstractModule——机制不含任何具体
 * opcode/事件知识）。具体 router 的 {@link #route} 是唯一 switch，同时完成「是否接收」
 * （default 返回 false，且不得碰 reader）与「怎么处理」（选定 Packet codec + Translator
 * 交给 {@link #emit}）。
 *
 * <p>日志收口（收侧唯一边界，对称发侧 Gms083.toLegacyPacket）：debug = 解码产物 JSON、
 * trace = 原始载荷 hex。
 */
public abstract class AbstractInRouter {

    private static final Logger log = LoggerFactory.getLogger(AbstractInRouter.class);

    /**
     * 唯一入口：单 switch 完成「是否接收」与处理。不接收返回 false（reader 未动）；
     * 接收即处理并返回 true。同一 opcode 的 case 集合在各 router 间互斥（独占 case 保证）。
     */
    public abstract boolean route(RecvOpcode opcode, ByteBufReader in, Player player);

    /**
     * 编排模板：decode（null = 整包静默丢）→ 日志 → translate（纯映射，先行——副作用
     * 可能依赖 packet 内容与翻译结果）→ beforeEmit → dispatch（同步执行 gameplay）→
     * afterEmit（unlock 类回包）。
     */
    protected final <P> void emit(RecvOpcode opcode, ByteBufReader in, InCodec<P> codec,
                                  Supplier<? extends InTranslator<P>> factory, Player player) {
        P packet = codec.decode(in);
        if (packet == null) {
            return;   // 整包静默丢（空移动序列/未识别 command，现状语义）
        }
        logDecode(opcode, packet, in);
        InTranslator<P> translator = factory.get();   // 每包一实例
        ClientEvent event = translator.translate(packet);
        translator.beforeEmit(packet, player);
        if (event != null) {
            ClientEventDispatcher.dispatch(player, event);
        }
        translator.afterEmit(packet, player);
    }

    /**
     * strict canary 窗口（原 shim 机制平移，知识在 case、机制在基类）：窗口内置位
     * strictMode，期间经 CharacterRef 直调本体（含 unref 解包）即断言失败——fail-safe
     * 记日志（doc/16 §4.1）。
     */
    protected final void strictWindow(Player player, Runnable body) {
        Character chr = player.character();
        chr.setStrictMode(true);
        try {
            body.run();
        } finally {
            chr.setStrictMode(false);
        }
    }

    private static <P> void logDecode(RecvOpcode opcode, P packet, ByteBufReader in) {
        if (log.isDebugEnabled()) {
            // record 携带实体引用时 JSON 序列化可能撞对象图循环（fastjson2 层级上限）——
            // 诊断日志不得有任何抛出路径（对称 toLegacyPacket 的降级策略）
            String json;
            try {
                json = JSON.toJSONString(packet);
            } catch (Throwable t) {
                json = "<unserializable>";
            }
            log.debug("[remote-in] {} {}", opcode, json);
        }
        if (log.isTraceEnabled()) {
            log.trace("[remote-in] {} hex {}", opcode, HexTool.toHexString(in.getBytes()));
        }
    }
}
