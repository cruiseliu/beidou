package org.gms.client.scripting;

import org.gms.client.character.Character;

/**
 * ESM 任务脚本的对话会话 API（per 会话实例，doc/13 权责设计）：
 * <b>零可变状态</b>——仅会话身份常量（owner/npc/entry/scriptPath）；状态机归 JS
 * 模块闭包，游戏状态归 chr 组件。全部方法在 player strand 上执行（actor 自身访问）。
 *
 * <p><b>权责边界（对话页外移后收窄）</b>：只承载"会话身份 + 会话控制 + showInfo"；
 * 对话页渲染（send* 族）归 {@code player.talk}（TalkApi，npc id 由脚本提供，会话
 * 无绑定）；任务状态推进归 {@code player.quest}；角色侧操作（道具/经验/通知）脚本经
 * 全局 {@code player} 直调 Character 门面，不经过本类。方法面白名单 = 会话语义准入：
 * 绑定会话身份或对话流的才进得来。questId 不在会话内——模块路径以首入冻结的
 * scriptPath 为准，导出函数的 questId 参数由调用方（start/end 带真值，more 传占位）
 * 显式传递。
 */
public final class QuestApi_OLD {

    private final Character owner;
    private final int npc;
    private final String entry;                    // 重入函数名："start" | "end"
    /** 模块路径（actorscripts 相对；首入时冻结，more 重入免重算） */
    private final String scriptPath;

    QuestApi_OLD(Character owner, int npc, String entry, String scriptPath) {
        this.owner = owner;
        this.npc = npc;
        this.entry = entry;
        this.scriptPath = scriptPath;
    }

    public int npc() {
        return npc;
    }

    String entry() {
        return entry;
    }

    String scriptPath() {
        return scriptPath;
    }

    Character owner() {
        return owner;
    }

    /** 过场 UI 图（借 item-inchat 帧发 UI 路径 + 动作锁解除归 basic 模块）；会话演出，
     * 不涉 npc 绑定，故留会话侧（对话页 send* 族已外移 TalkApi）。 */
    public void showInfo(String path) {
        owner.getRemote().npc().showInfo(path);
        owner.getRemote().basic().unlockActions();
    }

    // ── 会话控制 ──

    /**
     * 终结对话：解除会话登记。脚本侧无可重置状态（异步模型状态寿命 =
     * 会话 Promise 链，随终结自然消亡；帧合并已归 remote batch，无延迟队列可冲刷）。
     */
    public void dispose() {
        owner.getNpcInteract().clearEsmQuest(this);
    }
}
