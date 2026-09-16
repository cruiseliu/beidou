package org.gms.remote;

import org.gms.client.Player;

/**
 * 语义域入站接收器（C→S，语义层每模块一个）：接收本 module 的 ClientEvent 并解包为
 * 裸参数直调 gameplay Handler（槽位表挂 Player）。不承载实现层副作用——unlock/echo
 * 等回包归版本 translator 的副作用钩子。receive 只匹配自己 {@link #module()} 声明域
 * 的事件（禁止不分模块的超级接收器）；default 分支 = 声明与分支不一致的语义 bug。
 */
public interface ClientEventReceiver {

    /** 本接收器服务的语义域（dispatcher 查表键；装配期重复注册 fail-fast） */
    Module module();

    /** 交付一条本模块事件（player 为当前会话，receiver 经槽位表触达 gameplay） */
    void receive(Player player, ClientEvent event);
}
