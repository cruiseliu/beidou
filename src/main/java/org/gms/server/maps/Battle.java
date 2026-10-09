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
     *
     * @param dropEntitlement 掉落权益快照（用户侧最终倍率/卡倍率，phase 1 逐刀刷新）
     */
    public record CloseRangeAttackIntent(
            CharacterRef attacker,
            int cid, int skill, int skilllevel, int stance,
            int numAttackedAndDamage, int speed, int direction, int display,
            long dmgMax, boolean canCrit,
            Map<Integer, List<Integer>> declaredDamage,
            DropEntitlement dropEntitlement) {
    }

    /**
     * 掉落权益快照（用户侧倍率终值，phase 1 本体直读构造；map 侧按 dropOwner 取最近一刀）。
     * float→double 化登记：源头值精确加宽，下游滚动改 double 运算，中间舍入消失——RNG
     * 阈值 sub-ulp 漂移，无 wire 影响（掉落从不在 wire 确定集合）。
     *
     * @param dropRate     用户侧最终乘算倍率：getDropRate ×(familyBuff ? familyDrop : 1)；
     *                     boss 统一用此值（bossDropRate 取消）
     * @param mesoRate     meso 金额轴终值：getMesoRate ×(4111001 MESO_UP buff ? 值/100 : 1)
     * @param mesoDropRate meso 概率轴（图鉴卡，getCardRate(0)）——乘 chance，与 mesoRate 不同轴
     * @param cardRates    物品卡倍率（phase 1 对照静态掉落表逐条目解析，只收 ≠1.0 命中项；
     *                     不含 meso）。view miss 的目标无条目 → 缺省 1.0（登记在案）
     */
    public record DropEntitlement(
            double dropRate,
            double mesoRate,
            double mesoDropRate,
            List<PerItemDropRate> cardRates) {

        public record PerItemDropRate(int mobId, int itemId, double rate) {
        }

        /** 物品条目卡倍率（payload 个位数量级，线性扫）；缺省 1.0 */
        public double itemCardRate(int mobId, int itemId) {
            for (PerItemDropRate r : cardRates) {
                if (r.mobId() == mobId && r.itemId() == itemId) {
                    return r.rate();
                }
            }
            return 1.0;
        }
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

            // 交战登记：HP 条受众候选（攻击者；同队角色由广播端按视图 partyId 解析）
            monster.addEngaged(intent.cid());
            // 掉落权益快照登记（每刀覆盖为最新；击杀结算按 dropOwner 取）
            monster.rememberDropEntitlement(intent.cid(), intent.dropEntitlement());

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
