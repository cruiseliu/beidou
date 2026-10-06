package org.gms.client.scripting;

import org.gms.client.character.Character;
import org.gms.scripting.JsModule;

/**
 * ESM 任务脚本的对话会话 API（per 会话实例，doc/13 权责设计）：
 * <b>零可变状态</b>——仅会话身份常量（owner/npc/entry/scriptPath）；状态机归 JS
 * 模块闭包，游戏状态归 chr 组件。全部方法在 player strand 上执行（actor 自身访问）。
 *
 * <p><b>权责边界（对话页外移后收窄）</b>：只承载"会话身份 + 会话控制 + 入口调用 +
 * showInfo"；对话页渲染（send* 族）归 {@code player.talk}（TalkApi，npc id 由脚本
 * 提供，会话无绑定）；任务状态推进归 {@code player.quest}；角色侧操作（道具/经验/
 * 通知）脚本经全局 {@code player} 直调 Character 门面，不经过本类。方法面白名单 =
 * 会话语义准入：绑定会话身份或对话流的才进得来。questId 不在会话内——模块路径以
 * 首入冻结的 scriptPath 为准，导出函数的 questId 参数由调用方（start/end 带真值，
 * more 传占位）显式传递。
 */
public final class InteractContext {

    private final Character owner;
    private final int npcId;

    /** 模块路径（actorscripts 相对；首入时冻结，more 重入免重算） */
    private final String scriptPath;
    private final String scriptEntry;                    // 重入函数名："start" | "end"

    /**
     * 创建对话上下文。<b>管理入口归 {@link org.gms.client.character.CharacterNpcInteract#beginContext}</b>
     * ——创建即登记（会话槽位随建随挂），勿在组件外散建；构造公开仅为跨包工厂可达。
     */
    public InteractContext(Character owner, int npcId, String path, String entry) {
        this.owner = owner;
        this.npcId = npcId;
        this.scriptPath = path;
        this.scriptEntry = entry;
    }

    /** 会话 npc（interaction.js 开场做脚本 npc 一致性断言 + 转交瀑布函数） */
    public int getNpcId() {
        return npcId;
    }

    /**
     * 调用本上下文的脚本入口（首入与重入共用，原 QuestScript.invoke 收编）：
     * {@code args} 显式随调用传递（questId 居首），本上下文以末位追加——JS 入口签名
     * {@code (questId, mode, type, selection, ctx)}。
     *
     * <p><b>线程模型</b>：经宿主 run/call 串行进 Context（须在 player strand 上调用）。
     * 一拍对话 = 一个合并域（doc/package-client.md §2）：段内脚本的任务操作与对话页
     * 同批出门，段尾 commit——对话页必先于挂起到达客户端（等回复前必须已渲染），
     * 脚本异常/ESC 照常提交（状态已生效，帧须如实反映）。
     *
     * @return 入口函数返回值（瀑布形态下为 Promise/undefined，调用方一般不消费）
     */
    public Object call(Object... args) {
        return owner.getScriptRunner().call(() -> {
            try (var batch = owner.getRemote().batch()) {
                JsModule module = owner.getScriptRunner().moduleFor(scriptPath);
                Object[] all = new Object[args.length + 1];
                System.arraycopy(args, 0, all, 0, args.length);
                all[args.length] = this;
                return module.call(scriptEntry, all);
            }
        });
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
        owner.getNpcInteract().clearContext(this);
    }
}
