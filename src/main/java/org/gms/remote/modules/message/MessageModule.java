package org.gms.remote.modules.message;

import org.gms.remote.AbstractModule;
import org.gms.remote.modules.message.server.ShowHintEvent;
import org.gms.remote.modules.message.server.ShowInfoEvent;

/**
 * 语义模块：客户端提示消息域（教学 balloon、过场 UI 图等 self 流演出帧）。
 */
public abstract class MessageModule extends AbstractModule {

    /**
     * 教学提示 balloon。解锁（unlock STAT_CHANGED）随本语义由版本 wire 拼装发出——
     * 脚本门场景客户端锁输入，调用方无需关心。width &lt; 1 / height &lt; 5 的尺寸归一归版本 wire。
     */
    public final void showHint(String message, int width, int height) {
        post(new ShowHintEvent(message, width, height));
    }

    /**
     * 过场 UI 图（借 item-inchat 帧发 WZ UI 路径）。与 showHint 同款双包：解锁
     * （unlock STAT_CHANGED）随本语义由版本 wire 拼装发出，调用方无需关心。
     */
    public final void showInfo(String path) {
        post(new ShowInfoEvent(path));
    }
}
