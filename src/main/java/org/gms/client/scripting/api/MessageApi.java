package org.gms.client.scripting.api;

import org.gms.client.character.Character;

/**
 * 脚本 API 白名单——提示消息面（showHint）。经语义模块下发（remote message 模块 →
 * ShowHintEvent → MessageRouter 拼包），脚本不触达 PacketCreator（wire 禁令，
 * doc/script-engine.md §4.7）；解锁随语义由版本 wire 拼装，本类不感知。
 *
 * <p><b>线程模型</b>：全部方法在 owning player strand 上调用（脚本宿主串行进
 * Context，与 {@link org.gms.client.scripting.QuestApi} 同纪律）。
 * <b>零可变状态</b>——仅持有 Character 引用；对应 JS 侧 {@code player.message.showHint(...)}
 * （bind_player.js 分面；旧形态 {@code player_old.getRemote().message().showHint} 的收窄）。
 */
public final class MessageApi {

    private final Character chr;

    public MessageApi(Character chr) {
        this.chr = chr;
    }

    /** 屏幕上方提示条（教学指引等）；宽高为渲染像素，语义归客户端。 */
    public void showHint(String message, int width, int height) {
        chr.getRemote().message().showHint(message, width, height);
    }
}
