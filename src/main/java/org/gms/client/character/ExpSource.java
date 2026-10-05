package org.gms.client.character;

/**
 * 经验来源语义（GainExpEvent 载荷，gameplay 侧词汇）：版本 translator 据此决定演出形态
 * （显示方式/白字/聊天栏）。增量添加来源时 switch 穷尽性强制补显示分支。
 */
public enum ExpSource {
    /** 任务完成/任务动作奖励（in-chat 显示，非白字） */
    QUEST
}
