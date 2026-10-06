package org.gms.client.scripting.api;

import org.gms.client.Player;

/**
 * 脚本 API 白名单——提示消息面（showHint）。经语义模块下发（remote message 模块 →
 * ShowHintEvent → MessageRouter 拼包），脚本不触达 PacketCreator（wire 禁令，
 * doc/script-engine.md §4.7）；解锁随语义由版本 wire 拼装，本类不感知。
 *
 * <p><b>线程模型</b>：脚本宿主把全部执行串行在 owning player strand 上；本类方法经
 * {@link Player#require} 现取 actor context（off-strand 响亮失败），character 派生视图
 * 零存槽。<b>无状态</b>——对应 JS 侧 {@code player.message.showHint(...)}
 * （旧形态 {@code player_old.getRemote().message().showHint} 的收窄）。
 */
public final class MessageApi {

    /** 屏幕上方提示条（教学指引等）；宽高为渲染像素，语义归客户端。 */
    public void showHint(String message, int width, int height) {
        Player.require("MessageApi.showHint").character().getRemote().message().showHint(message, width, height);
    }
}
