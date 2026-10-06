package org.gms.client.scripting.api;

import org.gms.client.Player;
import org.gms.client.character.Character;

/**
 * 脚本 API 显式范围的 Java 侧聚合（player actor）：六分面 public 字段，JS 侧
 * {@code player.basic.isMale()} / {@code player.stats.getHp()} /
 * {@code player.inventory.gainItem(id)} / {@code player.message.showHint(...)} 或
 * {@code showInfo(path)} /
 * {@code player.talk.sendNext(ctx, text)} / {@code player.quest.forceStartQuest(id, npc)}。
 * 聚合只圈范围（字段面 = 脚本可见面），语义与线程纪律归各 Api 类。
 *
 * <p><b>构造与状态</b>：无参工厂——bind_player.js 模块顶层 {@code new PlayerApis()}
 * eager 构造（不惰性）。分面全部无状态（不持 Character），构造不触角色，装载期安全；
 * character 由各方法经 actor context（{@link org.gms.client.Player}）现取，不存槽。
 */
public final class PlayerApis {

    public final BasicApi basic;
    public final StatsApi stats;
    public final InventoryApi inventory;
    public final MessageApi message;
    public final TalkApi talk;
    public final QuestApi quest;

    public PlayerApis() {
        Character chr = Player.require("script_api").character();
        this.basic = new BasicApi(chr);
        this.stats = new StatsApi();
        this.inventory = new InventoryApi(chr);
        this.message = new MessageApi();
        this.talk = new TalkApi(chr);
        this.quest = new QuestApi();
    }
}
