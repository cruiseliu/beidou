/**
 * 地图脚本共享库（map/common）：bind_player.js 只放纯 ECMAScript 写不出来的 Java 绑定，
 * 组合式 util 与教学门工厂归本目录。
 *
 * 地图模块（actorscripts/map/<MAP_ID>.js）按 WZ portal script 名同名导出：
 *   export const advice00 = advice(0);
 *   export const adviceMap = mapAdvice();
 * 宽高沿用官方旧脚本（scripts/portal/advice*.js）原文参数；返回 true = 门已处理。
 */
import { getScriptStrings, player_old as player } from "../../lib/bind_player.js";

/**
 * 教学提示 balloon：解锁已并入 ShowHint 语义（版本 wire 拼装 HINT + unlock 双包），
 * 脚本只管显示。
 */
export function showInstruction(message, width, height) {
    player.getRemote().message().showHint(message, width, height);
}

/** adviceNN → [宽, 高]（官方旧脚本原文） */
const ADVICE_BOX = {
    0: [250, 5],
    1: [100, 5],
    2: [100, 5],
    3: [350, 5],
    4: [100, 5],
    5: [250, 5],
    6: [230, 5],
    7: [350, 5],
    8: [350, 5],
    9: [450, 6],
};

/** adviceNN 工厂：显示 SCRIPTSTRING_TUTORIAL_<n>（ScriptString 键带 $ 包裹） */
export function advice(n) {
    const [width, height] = ADVICE_BOX[n];
    return () => {
        showInstruction(getScriptStrings("tutorial").get("$SCRIPTSTRING_TUTORIAL_" + n + "$"), width, height);
        return true;
    };
}

/** adviceMap 工厂：显示 SCRIPTSTRING_TUTORIAL_45 */
export function mapAdvice() {
    return () => {
        showInstruction(getScriptStrings("tutorial").get("$SCRIPTSTRING_TUTORIAL_45$"), 230, 5);
        return true;
    };
}
