/**
 * InteractionManager —— ESM 对话会话框架件（纯 JS，对 Java 不可见，doc/13 §16）。
 *
 * 把 NPC_TALK_MORE 事件流翻译成 async/await 瀑布：脚本 await 一个对话原语即挂起，
 * manager 登记期望事件；事件（mode/type，selection 本层暂不消费）到达时 resolve/reject。
 * 续体是微任务，GraalJS 在宿主调用返回点排空微任务队列——续体不出 dispatch 所在的
 * strand 任务，CharacterScriptRunner 的 strand 串行契约因此保持。
 *
 * 事件词汇（gms083 实测，见 work/ref/quest-script.log）：mode 1 = 下一步/接受；
 * 0 = 上一步/拒绝；-1（0xFF）= ESC 关闭。单按钮页（仅下一步 00 01、仅上一步 01 00）的
 * 按钮点击同样发 mode 1——"仅上一步"末页因此能推进瀑布（原版 sendPrev 页的收尾衔接）。
 * type 是客户端回显的当前对话框类型（0 = talk，0x0C = accept/decline），用于对口分发。
 */

import { player, getScriptStrings } from "./bind.js";

/**
 * 官方脚本文本的字面取用视图（纯 JS wrapper，key 格式解释在此，基础取表归 bind.js 的
 * getScriptStrings）：i18n.quest0[5] = quest0.xml 的 "$SCRIPTSTRING_QUEST0_5$"。
 * 两层全惰性——第一层（文件名）只建代理不触数据（模块装载期安全），第二层首次索引访问
 * 才装表（per-file 解析一次，序号来自键尾 _N$，_MOBILE 等变体键自然不匹配）。
 * 文件/键缺失抛错；非数字键抛错（typo 响亮失败）；symbol 探测返回 undefined。
 * 保留字 i18n.EXP = quest0 的 SCRIPTSTRING_QUEST_TEXT_1（"经验值"，任务奖励尾巴拼装）。
 */
const EXP_KEY = "$SCRIPTSTRING_QUEST_TEXT_1$";
const i18nFiles = new Map();

export const i18n = new Proxy({}, {
    get(_target, file) {
        if (typeof file !== "string") {
            return undefined;
        }
        if (file === "EXP") {
            const exp = getScriptStrings("quest0").get(EXP_KEY);
            if (exp == null) {
                throw new Error("i18n: 缺少 EXP 文本（quest0 " + EXP_KEY + "）");
            }
            return exp;
        }
        let view = i18nFiles.get(file);
        if (view == null) {
            let indexTable = null;   // 序号 → 文本（首次索引访问才装表）
            view = new Proxy({}, {
                get(_t, index) {
                    if (typeof index === "symbol") {
                        return undefined;
                    }
                    if (!/^\d+$/.test(index)) {
                        throw new Error("i18n: 索引须为非负整数: " + file + "#" + String(index));
                    }
                    if (indexTable == null) {
                        indexTable = new Map();
                        for (const [key, text] of getScriptStrings(file)) {
                            const m = /_(\d+)\$$/.exec(key);
                            if (m != null) {
                                indexTable.set(Number(m[1]), text);
                            }
                        }
                    }
                    const text = indexTable.get(Number(index));
                    if (text == null) {
                        throw new Error("i18n: 无此脚本文本: " + file + "#" + index);
                    }
                    return text;
                },
            });
            i18nFiles.set(file, view);
        }
        return view;
    },
});

/** 对话被用户关闭（ESC）。挂起的 await 以本异常 reject；入口包装层随后自动 dispose。 */
export class DialogClosed extends Error {
    constructor() {
        super("dialog closed by user");
        this.name = "DialogClosed";
    }
}

/**
 * sendPages 哨兵：声明"末页保留下一步"（01 01，按下一步整链才 resolve，上一步仍可回退）。
 * 缺省末页只渲染上一步（01 00，原版 sendPrev 页；按钮点击即整链 resolve）——末页样式
 * 是调用方知识（原版脚本末拍有 sendNextPrev/sendPrev 两种），位置推导不出来。
 */
export const NEXT_PREV = Symbol("nextPrev");

/** accept/decline 对话框的 gms083 类型字节（客户端在 NPC_TALK_MORE 回显） */
const ACCEPT_DECLINE = 0x0C;

export class InteractionManager {

    #startFn;         // 会话瀑布函数 (interact, quest) => Promise
    #q = null;        // 当前会话身份（QuestApi 实例）；实例变化 = 新会话开场
    #pending = null;  // 挂起的对话（pages 链位置 + resolve/reject）；null = 无对话

    constructor(startFn) {
        this.#startFn = startFn;
    }

    /**
     * ESM 入口（QuestScript 首入与 NPC_TALK_MORE 重入共用，(mode, type, selection, q) 原样）。
     * 首入 (1, 0, 0) 与"下一步"事件同形，无法从事件本身区分——以会话身份判别：
     * QuestApi 实例变化 → 新会话开场（跑瀑布函数，完成/被关闭后自动 dispose）；
     * 同会话 → 事件解释：驱动挂起 await 或 pages 链内推进（不回脚本）。
     */
    entry = (mode, type, selection, q) => {
        if (q !== this.#q) {
            this.#begin(q);
        } else {
            this.#dispatch(mode, type);
        }
    };

    #begin(q) {
        this.#q = q;
        this.#pending = null;
        const quest = {
            forceStart: () => player.forceStartQuest(q.questId(), q.npc()),
            forceComplete: () => player.forceCompleteQuest(q.questId(), q.npc()),
        };
        // 瀑布终结（正常 return / DialogClosed / 脚本异常 reject）即 dispose——脚本 reset
        // 钩子 + 会话清除 + NPC 冷却。DialogClosed（ESC）是正常用户行为，静默收尾；
        // 其余异常重新抛出——不被本层吞掉。吞掉即静默死：微任务续体的 rejection 成
        // 悬空无处理者，宿主/strand 均无感知（strict canary 排查实证）；重抛的 rejection
        // 由 context 的 js.unhandled-rejections=throw 在微任务排空点转 PolyglotException
        // 出宿主调用，落 CharacterScriptRunner 的 fail-safe ERROR 日志。
        this.#startFn(this.#interact(q), quest).then(
            () => q.dispose(),
            (e) => {
                q.dispose();
                if (!(e instanceof DialogClosed)) {
                    throw e;
                }
            },
        );
    }

    #interact(q) {
        const self = this;
        return {
            /** "上一步/下一步"连播链：整链一个 await，链内回退不跨越 await（脚本零状态）。 */
            sendPages(pages, lastStyle) {
                return self.#ask(pages, "pages", 0, lastStyle);
            },
            /** 单页"下一步"（00 01）；按下一步 resolve。 */
            sendNext(text) {
                return self.#ask([text], "pages", 0, NEXT_PREV);
            },
            /** 接受/拒绝：接受 → true（mode 1），拒绝 → false（mode 0），ESC → reject。 */
            sendAcceptDecline(text) {
                return self.#ask([text], "confirm", ACCEPT_DECLINE);
            },
            /** 过场 UI 图（透传会话 API）。 */
            showInfo(path) {
                q.showInfo(path);
            },
        };
    }

    /** 登记挂起对话并渲染首页。瀑布破坏（上一对话未结束就发下一对话）是脚本错误，响亮失败。 */
    #ask(pages, kind, type, lastStyle) {
        if (this.#pending !== null) {
            throw new Error("interaction: 上一对话尚未结束");
        }
        if (!Array.isArray(pages) || pages.length === 0 || pages.some((p) => typeof p !== "string")) {
            throw new Error("interaction: pages 须为非空字符串数组");
        }
        const self = this;
        return new Promise((resolve, reject) => {
            self.#pending = { kind, type, lastStyle, pages, index: 0, resolve, reject };
            self.#render();
        });
    }

    /** 渲染当前页：按钮样式由位置推导（首页 00 01 / 中间 01 01），末页由哨兵决定。 */
    #render() {
        const pd = this.#pending;
        const text = pd.pages[pd.index];
        if (pd.kind === "confirm") {
            this.#q.sendAcceptDecline(text);
        } else if (pd.index === pd.pages.length - 1 && pd.lastStyle !== NEXT_PREV) {
            this.#q.sendPrev(text);
        } else if (pd.index === 0) {
            this.#q.sendNext(text);
        } else {
            this.#q.sendNextPrev(text);
        }
    }

    /** 事件解释（NPC_TALK_MORE）：mode -1 关闭；1 下一步；0 上一步。type 不对口的事件忽略。 */
    #dispatch(mode, type) {
        const pd = this.#pending;
        if (pd === null || type !== pd.type) {
            return;
        }
        if (mode === -1) {
            this.#pending = null;
            pd.reject(new DialogClosed());
        } else if (pd.kind === "confirm") {
            this.#pending = null;
            pd.resolve(mode === 1);
        } else if (mode === 1) {
            if (pd.index < pd.pages.length - 1) {
                pd.index += 1;
                this.#render();
            } else {
                this.#pending = null;   // 末页下一步 → 整链 resolve
                pd.resolve();
            }
        } else if (mode === 0 && pd.index > 0) {
            pd.index -= 1;
            this.#render();
        }
    }
}
