package org.gms.remote;

import java.util.ArrayList;
import java.util.List;

/**
 * 事务作用域的语义事件段：append-only 原始序列 + 父段链接。
 * <ul>
 *   <li>嵌套：子段 close 时整段拼入父段（代数化等价于旧 checkpoint 标记栈）；</li>
 *   <li>丢弃：drop 直接弃置子段引用，O(1)，无一字节需要理解；</li>
 *   <li>提交：最外层段按序分发后端处理，随后固定序冲刷。</li>
 * </ul>
 * 远端实现的内部件；不构成对外契约。
 */
public final class ScopeLog {
    private final ScopeLog parent;
    private final List<SemanticEvent> events = new ArrayList<>();

    public ScopeLog(ScopeLog parent) {
        this.parent = parent;
    }

    public void append(SemanticEvent e) {
        events.add(e);
    }

    /** 子段并入父段末尾（嵌套关闭路径）；子段随之废弃 */
    public void adopt(ScopeLog child) {
        events.addAll(child.events);
    }

    public List<SemanticEvent> events() {
        return events;
    }

    public ScopeLog parent() {
        return parent;
    }
}
