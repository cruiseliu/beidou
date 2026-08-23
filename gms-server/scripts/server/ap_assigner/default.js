/*
 * AP 自动分配器脚本（Ronan Lana autoassigner 的 JS 移植）
 *
 * 加载方式（ESM，见 org.gms.scripting.JsModule）：
 *   JsModule m = JsModule.importModule("server/ap_assigner/default.js");
 *   Value assigner = m.getDefault();   // APAssigner 对象
 *   Value assign  = assigner.getMember("default");   // 默认分配函数
 *
 * 对外接口：
 *   APAssigner.default(ctx) -> { str, dex, int, luk }
 *   APAssigner.beginner(ctx) -> 同上（新手也走同一套逻辑）
 *     ctx = {
 *       level: 当前等级,
 *       str/dex/int/luk: 当前基础四维(不含装备),
 *       ap: 剩余 AP,
 *       jobStyle: 'MAGICIAN'|'BOWMAN'|'CROSSBOWMAN'|'THIEF'|'GUNSLINGER'|其它,
 *       eqpStrList/eqpDexList/eqpLukList: 装备加成列表(已按降序排好),
 *       eqpStr/eqpDex/eqpLuk: 装备四维总和,
 *       useSecondaryCap: 是否启用副属性上限,
 *       maxAp: 属性上限
 *     }
 *   返回 { str, dex, int, luk } 为本次应分配的 AP 点数。
 *
 * 与原 Java 实现的对应关系（AssignAPProcessor.APAutoAssignAction）：
 *   - 职业分支：MAGICIAN / BOWMAN / GUNSLINGER+CROSSBOWMAN / THIEF / default
 *   - 分支内只计算 prStat/scStat/trStat 局部量，statGain 在最后 gainStatByType 阶段填充
 *   - statGain 下标：0=STR 1=DEX 2=LUK 3=INT（与 Java 端 chr.assignStrDexIntLuk 的参数顺序一致）
 */

function nthHighest(list, rank) {
    return list.length <= rank ? 0 : list[rank];
}

function gainStatByType(type, statGain, gain, statUpdate, maxAp) {
    if (gain <= 0) {
        return 0;
    }
    let newVal = 0;
    const idx = { STR: 0, DEX: 1, LUK: 2, INT: 3 }[type];
    newVal = statUpdate[idx] + gain;
    if (newVal > maxAp) {
        statGain[idx] += (gain - (newVal - maxAp));
        statUpdate[idx] = maxAp;
    } else {
        statGain[idx] += gain;
        statUpdate[idx] = newVal;
    }
    if (newVal > maxAp) {
        return newVal - maxAp;
    }
    return 0;
}

function quaternaryStat(jobStyle) {
    return jobStyle !== 'MAGICIAN' ? 'INT' : 'STR';
}

function assign(ctx) {
    // 装备属性收集（调用方已传入降序列表与总和）
    const eqpStr = ctx.eqpStr, eqpDex = ctx.eqpDex, eqpLuk = ctx.eqpLuk;
    const eqpStrList = ctx.eqpStrList, eqpDexList = ctx.eqpDexList, eqpLukList = ctx.eqpLukList;

    // 自动分配器会查看前两件装备的属性来计算最佳升级方案
    const eqpStrTop2 = nthHighest(eqpStrList, 0) + nthHighest(eqpStrList, 1);
    const eqpDexTop2 = nthHighest(eqpDexList, 0) + nthHighest(eqpDexList, 1);
    const eqpLukTop2 = nthHighest(eqpLukList, 0) + nthHighest(eqpLukList, 1);

    const jobStyle = ctx.jobStyle;
    const useSecondaryCap = ctx.useSecondaryCap;
    const maxAp = ctx.maxAp;

    const statGain = [0, 0, 0, 0];        // STR DEX LUK INT（最终填充）
    const statUpdate = [ctx.str, ctx.dex, ctx.luk, ctx.int]; // 当前基础属性

    const remainingAp = ctx.ap;
    if (remainingAp < 1) {
        return { str: 0, dex: 0, int: 0, luk: 0 };
    }

    let prStat = 0, scStat = 0, trStat = 0, temp, tempAp = remainingAp, CAP;

    let primary, secondary, tertiary = 'LUK';

    switch (jobStyle) {
        case 'MAGICIAN': { // 魔法师职业
            CAP = 165;
            scStat = (ctx.level + 3) - (ctx.luk + eqpLuk - eqpLukTop2);
            if (scStat < 0) scStat = 0;
            scStat = Math.min(scStat, tempAp);
            tempAp = tempAp > scStat ? tempAp - scStat : 0;
            prStat = tempAp;

            if (useSecondaryCap && scStat + ctx.luk > CAP) {
                temp = scStat + ctx.luk - CAP;
                scStat -= temp;
                prStat += temp;
            }
            primary = 'INT';
            secondary = 'LUK';
            tertiary = 'DEX';
            break;
        }
        case 'BOWMAN': { // 弓箭手职业
            CAP = 125;
            scStat = (ctx.level + 5) - (ctx.str + eqpStr - eqpStrTop2);
            if (scStat < 0) scStat = 0;
            scStat = Math.min(scStat, tempAp);
            tempAp = tempAp > scStat ? tempAp - scStat : 0;
            prStat = tempAp;

            if (useSecondaryCap && scStat + ctx.str > CAP) {
                temp = scStat + ctx.str - CAP;
                scStat -= temp;
                prStat += temp;
            }
            primary = 'DEX';
            secondary = 'STR';
            break;
        }
        case 'GUNSLINGER':
        case 'CROSSBOWMAN': { // 枪手/弩手职业
            CAP = 120;
            scStat = ctx.level - (ctx.str + eqpStr - eqpStrTop2);
            if (scStat < 0) scStat = 0;
            scStat = Math.min(scStat, tempAp);
            tempAp = tempAp > scStat ? tempAp - scStat : 0;
            prStat = tempAp;

            if (useSecondaryCap && scStat + ctx.str > CAP) {
                temp = scStat + ctx.str - CAP;
                scStat -= temp;
                prStat += temp;
            }
            primary = 'DEX';
            secondary = 'STR';
            break;
        }
        case 'THIEF': { // 盗贼职业
            CAP = 160;
            scStat = 0;
            if (ctx.dex < 80) {
                scStat = (2 * ctx.level) - (ctx.dex + eqpDex - eqpDexTop2);
                if (scStat < 0) scStat = 0;
                scStat = Math.min(80 - ctx.dex, scStat);
                scStat = Math.min(tempAp, scStat);
                tempAp -= scStat;
            }
            temp = (ctx.level + 40) - Math.max(80, scStat + ctx.dex + eqpDex - eqpDexTop2);
            if (temp < 0) temp = 0;
            temp = Math.min(tempAp, temp);
            scStat += temp;
            tempAp -= temp;

            // 盗贼只有在达到基于等级的阈值时才会分配 STR
            if (ctx.str >= Math.max(13, Math.floor(0.4 * ctx.level))) {
                if (ctx.str < 50) {
                    trStat = (ctx.level - 10) - (ctx.str + eqpStr - eqpStrTop2);
                    if (trStat < 0) trStat = 0;
                    trStat = Math.min(50 - ctx.str, trStat);
                    trStat = Math.min(tempAp, trStat);
                    tempAp -= trStat;
                }
                temp = (20 + Math.floor(ctx.level / 2)) - Math.max(50, trStat + ctx.str + eqpStr - eqpStrTop2);
                if (temp < 0) temp = 0;
                temp = Math.min(tempAp, temp);
                trStat += temp;
                tempAp -= temp;
            }
            prStat = tempAp;

            if (useSecondaryCap && scStat + ctx.dex > CAP) {
                temp = scStat + ctx.dex - CAP;
                scStat -= temp;
                prStat += temp;
            }
            if (useSecondaryCap && trStat + ctx.str > CAP) {
                temp = trStat + ctx.str - CAP;
                trStat -= temp;
                prStat += temp;
            }
            primary = 'LUK';
            secondary = 'DEX';
            tertiary = 'STR';
            break;
        }
        default: { // 战士、新手、拳手等默认职业
            CAP = 300;
            let highDex = false;
            if (ctx.level < 40) {
                if (ctx.dex >= (2 * ctx.level) + 2) highDex = true;
            } else {
                if (ctx.dex >= ctx.level + 42) highDex = true;
            }

            if (!highDex) {
                scStat = 0;
                if (ctx.dex < 80) {
                    scStat = (2 * ctx.level) - (ctx.dex + eqpDex - eqpDexTop2);
                    if (scStat < 0) scStat = 0;
                    scStat = Math.min(80 - ctx.dex, scStat);
                    scStat = Math.min(tempAp, scStat);
                    tempAp -= scStat;
                }
                temp = (ctx.level + 40) - Math.max(80, scStat + ctx.dex + eqpDex - eqpDexTop2);
                if (temp < 0) temp = 0;
                temp = Math.min(tempAp, temp);
                scStat += temp;
                tempAp -= temp;
            } else {
                scStat = 0;
                if (ctx.dex < 96) {
                    scStat = Math.floor(2.4 * ctx.level) - (ctx.dex + eqpDex - eqpDexTop2);
                    if (scStat < 0) scStat = 0;
                    scStat = Math.min(96 - ctx.dex, scStat);
                    scStat = Math.min(tempAp, scStat);
                    tempAp -= scStat;
                }
                temp = 96 + Math.floor(1.2 * (ctx.level - 40)) - Math.max(96, scStat + ctx.dex + eqpDex - eqpDexTop2);
                if (temp < 0) temp = 0;
                temp = Math.min(tempAp, temp);
                scStat += temp;
                tempAp -= temp;
            }
            prStat = tempAp;

            if (useSecondaryCap && scStat + ctx.dex > CAP) {
                temp = scStat + ctx.dex - CAP;
                scStat -= temp;
                prStat += temp;
            }
            primary = 'STR';
            secondary = 'DEX';
            break;
        }
    }

    // 实际执行属性分配（含 maxAp 截断后的重新分配）
    let extras = 0;
    extras = gainStatByType(primary, statGain, prStat + extras, statUpdate, maxAp);
    extras = gainStatByType(secondary, statGain, scStat + extras, statUpdate, maxAp);
    extras = gainStatByType(tertiary, statGain, trStat + extras, statUpdate, maxAp);

    if (extras > 0) {
        extras = gainStatByType(primary, statGain, extras, statUpdate, maxAp);
        extras = gainStatByType(secondary, statGain, extras, statUpdate, maxAp);
        extras = gainStatByType(tertiary, statGain, extras, statUpdate, maxAp);
        gainStatByType(quaternaryStat(jobStyle), statGain, extras, statUpdate, maxAp);
    }

    return { str: statGain[0], dex: statGain[1], int: statGain[3], luk: statGain[2] };
}

// 暴露给脚本宿主（按 apAutoAssignKey 分派：default 与 beginner 均走同一套 Ronan Lana 逻辑）
const APAssigner = {
    default: assign,
    beginner: assign,
};

export default APAssigner;
