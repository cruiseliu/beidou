package org.gms.remote.gms083.client.translate;

import org.gms.client.Player;
import org.gms.remote.ClientEvent;

/**
 * 收包翻译（版本侧，每数据包一个独立实例——有状态翻译的状态寿命 = 单包，无并发、
 * 无复位遗漏）。三段契约（编排序见 AbstractInRouter.emit）：
 *
 * <ul>
 *   <li>{@link #translate}：纯映射 wire record → ClientEvent（不读角色数据、不做域决策，
 *       no-peek 边界）；返回 null = 不产语义事件（echo 形态：服务端只回应、不消费语义）。</li>
 *   <li>{@link #beforeEmit}：dispatch 前的副作用钩子（回发 echo 等；可依赖 packet 内容
 *       与 translate 的结果/翻译态）。</li>
 *   <li>{@link #afterEmit}：dispatch 后的副作用钩子（unlock 回包等；此时 gameplay
 *       handler 已同步执行完毕）。</li>
 * </ul>
 */
public interface InTranslator<P> {

    /** 纯映射：wire record（版本词汇）→ 语义事件（int 词汇）；null = 零语义事件 */
    ClientEvent translate(P packet);

    /** dispatch 前副作用（默认无） */
    default void beforeEmit(P packet, Player player) {
    }

    /** dispatch 后副作用（默认无；unlock 类回包在此） */
    default void afterEmit(P packet, Player player) {
    }
}
