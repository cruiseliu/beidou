package org.gms.client.scripting;

import org.gms.client.character.Character;
import org.gms.remote.modules.npc.client.DialogButtons;

/**
 * ESM 任务脚本的对话会话 API（per 会话实例，doc/13 权责设计）：
 * <b>零可变状态</b>——仅会话身份常量（owner/quest/npc/entry）；状态机归 JS 模块闭包，
 * 游戏状态归 chr 组件。全部方法在 player strand 上执行（actor 自身访问）。
 *
 * <p><b>权责边界（§16 追记后收窄）</b>：只承载"对话页发包（绑定本会话 npc）+ 会话控制"；
 * 角色侧操作（道具/经验/任务状态/通知）脚本经全局 {@code player} 直调 Character 门面，
 * 不经过本类。方法面白名单 = 会话语义准入：绑定会话身份或对话流的才进得来。
 */
public final class QuestApi {

    private final Character owner;
    private final int questId;
    private final int npc;
    private final String entry;                    // 重入函数名："start" | "end"

    QuestApi(Character owner, int questId, int npc, String entry) {
        this.owner = owner;
        this.questId = questId;
        this.npc = npc;
        this.entry = entry;
    }

    public int questId() {
        return questId;
    }

    public int npc() {
        return npc;
    }

    String entry() {
        return entry;
    }

    Character owner() {
        return owner;
    }

    // ── 对话页（self 流，经 RemoteClient 编码直发本连接；样式字节契约见 NpcModule）──

    public void sendNext(String text) {
        owner.getRemote().npc().talk(npc, text, DialogButtons.NEXT, 0);
    }

    public void sendPrev(String text) {
        owner.getRemote().npc().talk(npc, text, DialogButtons.PREV_OK, 0);
    }

    public void sendNextPrev(String text) {
        owner.getRemote().npc().talk(npc, text, DialogButtons.PREV_NEXT, 0);
    }

    public void sendOk(String text) {
        owner.getRemote().npc().talk(npc, text, DialogButtons.OK, 0);
    }

    public void sendYesNo(String text) {
        owner.getRemote().npc().talk(npc, text, DialogButtons.YES_NO, 0);
    }

    public void sendAcceptDecline(String text) {
        owner.getRemote().npc().talk(npc, text, DialogButtons.ACCEPT_DECLINE, 0);
    }

    /** 过场 UI 图（借 item-inchat 帧发 UI 路径 + 动作锁解除归 basic 模块）。 */
    public void showInfo(String path) {
        owner.getRemote().npc().showInfo(path);
        owner.getRemote().basic().unlockActions();
    }

    // ── 会话控制 ──

    /**
     * 终结对话：解除会话登记 + NPC 冷却。脚本侧无可重置状态（异步模型状态寿命 =
     * 会话 Promise 链，随终结自然消亡；帧合并已归 remote batch，无延迟队列可冲刷）。
     */
    public void dispose() {
        owner.clearEsmQuest(this);
        owner.setNpcCooldown(System.currentTimeMillis());
    }
}
