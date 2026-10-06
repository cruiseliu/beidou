package org.gms.client.scripting;

import org.gms.client.character.Character;
import org.gms.scripting.JsModule;

/**
 * ESM 任务脚本的对话会话上下文（per 会话实例，doc/13 权责设计）：
 * <b>零可变状态</b>——仅会话身份常量（owner/npc/entry/scriptPath）；状态机归 JS
 * 模块闭包，游戏状态归 chr 组件。全部方法在 player strand 上执行（actor 自身访问）。
 *
 * <p><b>对 JS 为 opaque 令牌</b>：方法面不对 JS 开放（终结归 {@code player.talk.end}，
 * Java 侧公共面仅剩跨包路由所需的 getNpcId）；对话页渲染归 {@code player.talk}，
 * 任务状态推进归 {@code player.quest}，过场 UI 图归 {@code player.message}；角色侧
 * 操作（道具/经验/通知）脚本经全局 {@code player} 直调 Character 门面。会话生命周期
 * 管理归 CharacterNpcInteract（beginContext/clearContext）。questId 不在会话内——
 * 模块路径以首入冻结的 scriptPath 为准，导出函数的 questId 参数由调用方（start/end
 * 带真值，more 传占位）显式传递。
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
    Object call(Object... args) {
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
}
