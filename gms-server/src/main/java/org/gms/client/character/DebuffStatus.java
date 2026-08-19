package org.gms.client.character;

import org.gms.client.Disease;
import org.gms.server.life.MobSkill;

/** debuff 状态值对象：疾病类型 + 来源技能 + 生效时刻/时长。
 *  取代 org.gms.client.DiseaseValueHolder，并把原 Pair<DiseaseValueHolder, MobSkill> 合并为单值，
 *  作为 CharacterDebuffs.debuffs 的 map 值类型（key 即 Disease，type 冗余自描述）。 */
class DebuffStatus {
    /** 疾病类型（与 map key 一致，自描述） */
    public final Disease type;
    /** 来源技能（施加该 debuff 的怪物技能） */
    public final MobSkill source;
    /** 生效时刻 */
    public long startTime;
    /** 剩余时长 */
    public long length;

    public DebuffStatus(Disease type, MobSkill source, long startTime, long length) {
        this.type = type;
        this.source = source;
        this.startTime = startTime;
        this.length = length;
    }
}
