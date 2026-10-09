package org.gms.client.messages;

import org.gms.infra.ActorMessage;

/**
 * map → player：所参与击杀的怪物死亡了（逐参与者逐份投递——每人携带自己的贡献权重，
 * 非 broadcast）。map actor 只做团队结算（死亡归属/takenDamage 份额/level split/
 * SHOWDOWN 折算）与 rep 数值（家族声望增量，按 mob 属性计），个人修正（Holy
 * Symbol/rates/EXP buff/家族声望转账）由接收方 player actor 执行；mapId 供接收方
 * 校验自身所在图（同 HP 帧，异步边界权威判定在 Player actor）。
 */
public record MapMonsterKilledMessage(int mapId, int mobId, int mobLevel, float expWeight,
                                      float partyBonusWeight, boolean white, boolean hasPartySharers,
                                      float showdownMult, int familyRepGain) implements ActorMessage {
}
