package org.gms.server.maps;

import org.gms.client.character.CharacterRef;
import org.gms.infra.PipelineContext;
import org.gms.constants.id.MobId;
import org.gms.client.status.MonsterStatus;
import org.gms.net.packet.Packet;
import org.gms.server.life.Monster;
import org.gms.util.AssertUtil;
import org.gms.util.PacketCreator;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 近战攻击 phase 2 执行器（map actor 域内）：目标相关处理 + 伤害数值最终化 + 既有本地
 * 伤害管线复用 + 攻击中继广播。由 phase 1（player actor，{@link org.gms.client.character.CharacterBattle}）
 * 经 {@link MapleMapRef#applyCloseRangeAttack(CloseRangeAttackIntent)} post 进入，任务体
 * 全程在 map actor 上执行——mob 读写/受众迭代均本域直调（合法），attacker 仅身份 ref
 * （不触本体；exp/drop/死亡管线经 {@link MapleMap#damageMonster} 既有路径消费）。
 */
public final class Battle {

    private final MapleMap map;

    Battle(MapleMap map) {
        this.map = map;
    }

    /**
     * phase 1 → phase 2 语义载荷（player actor → map actor）。零活引用：attacker 为身份
     * ref；declaredDamage 为 LinkedHashMap——迭代序 = phase 1 冻结的原 HashMap.keySet()
     * 序 = 历史 relay 的 keySet 迭代序（多目标字节一致性），载荷视作不可变。
     */
    public record CloseRangeAttackIntent(
            CharacterRef attacker,
            int cid, int skill, int skilllevel, int stance,
            int numAttackedAndDamage, int speed, int direction, int display,
            long dmgMax, boolean canCrit,
            Map<Integer, List<Integer>> declaredDamage) {
    }

    /**
     * phase 2 主流程：逐目标「目标相关 slot（免疫/dojo boss 上限——全量版元素克制等在此
     * 展开）→ 伤害最终化（暴击反转 wire 约定在此定型：上限的最终值归本域才算齐）→
     * 既有本地伤害管线（aggro/hp/死亡/exp/drop/HP 帧原路产生）」。中继伤害序 = declared
     * 迭代序，字节与历史一致。
     */
    void applyCloseRangeAttack(CloseRangeAttackIntent intent) {
        Map<Integer, List<Integer>> relayDamage = new LinkedHashMap<>();
        for (Map.Entry<Integer, List<Integer>> entry : intent.declaredDamage().entrySet()) {
            final Monster monster = map.getMonsterByOid(entry.getKey());
            if (monster == null) {
                continue;
            }
            final List<Integer> declared = entry.getValue();

            // —— 目标相关 slot（历史 applyAttack 的 monster 状态守卫；全量版机制落点）——
            AssertUtil.isTrue(!monster.isBuffed(MonsterStatus.MAGIC_IMMUNITY));
            AssertUtil.isTrue(!monster.isBuffed(MonsterStatus.WEAPON_IMMUNITY));
            AssertUtil.isTrue(!MobId.isDojoBoss(monster.getId()));

            int totDamageToOneMonster = 0;
            List<Integer> finalized = new ArrayList<>(declared.size());
            for (Integer eachd : declared) {
                int damage = eachd;
                if (intent.canCrit() && damage > intent.dmgMax()) {
                    // 暴击反转（wire 约定）：超上限即视作暴击，负值呈现在客户端
                    damage = -Integer.MAX_VALUE + damage - 1;
                }
                finalized.add(damage);
                // 伤害累计的历史等价归一（反转值按同款偏移折回）
                if (damage < 0) {
                    damage += Integer.MAX_VALUE;
                }
                totDamageToOneMonster += damage;
            }

            monster.aggroMonsterDamage(intent.attacker(), totDamageToOneMonster);
            map.damageMonster(intent.attacker(), monster, totDamageToOneMonster);
            relayDamage.put(entry.getKey(), finalized);
        }

        final Packet relay = PacketCreator.closeRangeAttack(
                intent.cid(),
                intent.skill(),
                intent.skilllevel(),
                intent.stance(),
                intent.numAttackedAndDamage(),
                relayDamage,
                intent.speed(),
                intent.direction(),
                intent.display()
        );
        map.broadcastAttackRelay(intent.attacker(), relay);
    }
}
