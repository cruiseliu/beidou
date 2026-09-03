package org.gms.remote;

import java.util.ArrayList;
import java.util.List;

/**
 * 事务作用域的语义事件段：append-only 原始序列 + 父段链接（嵌套 adopt / drop O(1)）。
 * 远端实现的内部件；事务语义见 gms-server/doc/package-client.md §2。
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
