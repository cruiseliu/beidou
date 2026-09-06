package org.gms.scripting;

import org.gms.client.character.Character;
import org.gms.config.GameConfig;
import org.graalvm.polyglot.Value;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AP 自动分配脚本调用工具（统一 AssignAPProcessor 与升级自动分配的脚本调用）。
 *
 * 加载 scripts/server/ap_assigner/default.js（ESM），取 export default 的 APAssigner 对象，
 * 按 key（如 "default"/"beginner"）调用分配函数：
 *   APAssigner[key](ctx) -> { str, dex, int, luk }
 * ctx 字段与 default.js 一致：
 *   level/str/dex/int/luk/ap/jobStyle/eqpStrList/eqpDexList/eqpLukList/eqpStr/eqpDex/eqpLuk/useSecondaryCap/maxAp
 */
public final class ApAssignerScript {

    private static final String SCRIPT_PATH = "server/ap_assigner/default.js";

    private ApAssignerScript() {
    }

    /**
     * 调用脚本分配 AP。
     *
     * @param chr           角色（取 str/dex/int/luk/jobStyle）
     * @param key           分配器 key（对应 APAssigner 对象成员，如 "default"/"beginner"）
     * @param level         传给脚本的等级（升级自动分配传升级后等级；手动分配传当前等级）
     * @param remainingAp   剩余 AP（脚本分配上限）
     * @param eqpStrList    装备 STR 加成列表（降序；无装备传空）
     * @param eqpDexList    装备 DEX 加成列表（降序；无装备传空）
     * @param eqpLukList    装备 LUK 加成列表（降序；无装备传空）
     * @param eqpStr        装备 STR 加成总和（无装备传 0）
     * @param eqpDex        装备 DEX 加成总和（无装备传 0）
     * @param eqpLuk        装备 LUK 加成总和（无装备传 0）
     * @return {str, dex, int, luk} 分配量；脚本不可用或无 AP 时返回全 0
     */
    public static int[] assign(Character chr, String key, int level, int remainingAp,
                               List<Short> eqpStrList, List<Short> eqpDexList, List<Short> eqpLukList,
                               int eqpStr, int eqpDex, int eqpLuk) {
        if (key == null || key.isEmpty() || remainingAp < 1) {
            return new int[4];
        }

        // 脚本在 owner strand 上执行并等待结论（M1.5：per-client 宿主；异常/closed → null → 全 0）
        int[] out = chr.getScriptRunner().call(() ->
                assignInternal(chr, key, level, remainingAp, eqpStrList, eqpDexList, eqpLukList,
                        eqpStr, eqpDex, eqpLuk));
        return out != null ? out : new int[4];
    }

    private static int[] assignInternal(Character chr, String key, int level, int remainingAp,
                                        List<Short> eqpStrList, List<Short> eqpDexList, List<Short> eqpLukList,
                                        int eqpStr, int eqpDex, int eqpLuk) {
        JsModule module = chr.getScriptRunner().moduleFor(SCRIPT_PATH);
        Object def = module.getDefault();
        if (!(def instanceof Value assigner)) {
            return new int[4];
        }
        Value fn = assigner.getMember(key);
        if (fn == null || !fn.canExecute()) {
            return new int[4];
        }

        Map<String, Object> ctx = new HashMap<>();
        ctx.put("level", level);
        ctx.put("str", chr.getStr());
        ctx.put("dex", chr.getDex());
        ctx.put("int", chr.getInt());
        ctx.put("luk", chr.getLuk());
        ctx.put("ap", remainingAp);
        ctx.put("jobStyle", chr.getJobStyle().name());
        ctx.put("eqpStrList", eqpStrList);
        ctx.put("eqpDexList", eqpDexList);
        ctx.put("eqpLukList", eqpLukList);
        ctx.put("eqpStr", eqpStr);
        ctx.put("eqpDex", eqpDex);
        ctx.put("eqpLuk", eqpLuk);
        ctx.put("useSecondaryCap", GameConfig.getServerBoolean("use_auto_assign_secondary_cap"));
        ctx.put("maxAp", GameConfig.getServerInt("max_ap"));

        Value result = fn.execute(ctx);
        return new int[]{
                result.getMember("str").asInt(),
                result.getMember("dex").asInt(),
                result.getMember("int").asInt(),
                result.getMember("luk").asInt(),
        };
    }
}
