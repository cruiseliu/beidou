package org.gms.client.character;

/**
 * 经验来源语义（GainExpEvent 载荷，gameplay 侧词汇）：版本 translator 据此决定演出形态
 * （显示方式/白字/聊天栏）。增量添加来源时 switch 穷尽性强制补显示分支。
 */
public enum ExpSource {
    /** 任务完成/任务动作奖励（in-chat 显示，非白字） */
    QUEST,
    /** 击杀获得·白字（个人伤害占比 ≥ 均值+标准差阈值，map 侧死亡结算判定） */
    MONSTER,
    /** 击杀获得·非白字（黄字；贡献未达阈值/纯分享份额） */
    MONSTER_SHARE,
    /** 组队加成经验（party bonus；独立演出帧，非白字非 in-chat——与个人演出并入待 party 行接入） */
    PARTY_BONUS
}
