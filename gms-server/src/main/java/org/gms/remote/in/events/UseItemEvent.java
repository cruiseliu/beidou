package org.gms.remote.in.events;

/** PET_FOOD 解码结果：使用道具意图（slot = USE 背包槽位，itemId = 声明的道具 id）。
 *  目标宠物不在包内——由 gameplay 按角色状态选择/校验。 */
public record UseItemEvent(short slot, int itemId) implements ClientEvent {
}
