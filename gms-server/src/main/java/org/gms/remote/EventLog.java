package org.gms.remote;

import java.util.ArrayList;
import java.util.List;

/**
 * 事务作用域的语义事件段：append-only 原始序列（条目携带 owner 路由器）+ 父段链接
 * （嵌套 adopt / drop O(1)）。远端实现的内部件；事务语义见 gms-server/doc/package-client.md §2。
 */
public final class EventLog {
    public record Entry(ServerEventDest dest, ServerEventBase event) {}

    private final List<Entry> entries = new ArrayList<>();

    public void add(ServerEventDest dest, ServerEventBase event) {
        entries.add(new Entry(dest, event));
    }

    /** 父段收口：子段条目并入末尾（嵌套 adopt），子段随之废弃 */
    public void addAll(EventLog log) {
        entries.addAll(log.entries);
    }

    public List<Entry> entries() {
        return entries;
    }
}
