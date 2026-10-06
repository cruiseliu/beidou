package org.gms.client.scripting.api;

import org.gms.client.Player;

/**
 * 脚本 API 白名单——提示消息面（showHint/showInfo）。经语义模块下发（remote message
 * 模块 → 事件 → MessageRouter 拼包），脚本不触达 PacketCreator（wire 禁令，
 * doc/script-engine.md §4.7）；解锁（unlock STAT_CHANGED）随各语义由版本 wire 拼装
 * 双包，本类不感知。
 *
 * <p><b>线程模型</b>：脚本宿主把全部执行串行在 owning player strand 上；本类方法经
 * {@link Player#require} 现取 actor context（off-strand 响亮失败），character 派生视图
 * 零存槽。<b>无状态</b>——对应 JS 侧 {@code player.message.showHint(...)} /
 * {@code player.message.showInfo(path)}。
 */
public final class MessageApi {

    /** 屏幕上方提示条（教学指引等）；宽高为渲染像素，语义归客户端。 */
    public void showHint(String message, int width, int height) {
        Player.require("MessageApi.showHint").character().getRemote().message().showHint(message, width, height);
    }

    /** 过场 UI 图（借 item-inchat 帧发 WZ UI 路径；原 InteractContext.showInfo 迁入，
     *  unlock 从 basic 域合并 flush 改为随语义直发双包，wire 主帧不变）。 */
    public void showInfo(String path) {
        Player.require("MessageApi.showInfo").character().getRemote().message().showInfo(path);
    }
}
