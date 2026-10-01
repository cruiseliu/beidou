package org.gms.remote.modules.basic.server;

import org.gms.client.character.Character;

/**
 * 进图初始化（SET_FIELD 主包 + 键位表 KEYMAP——客户端视图初始化语义）。
 * 冻结不完整：chr 活引用 + wire 事实派生发生在 deliver 时点（合并域内 = 提交时刻，
 * 晚于构造），完整的入域时冻结以后再修（router 处 FIXME）。见 doc/12 §21 追记 5。
 */
public record InitializeEvent(Character chr) implements BasicEvent {
}
