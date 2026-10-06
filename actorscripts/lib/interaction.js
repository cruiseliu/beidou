/**
 * InteractionManager —— ESM 对话会话框架件（纯 JS，对 Java 不可见，doc/13 §16）。
 *
 * 把 NPC_TALK_MORE 事件流翻译成 async/await 瀑布：脚本 await 一个对话原语即挂起，
 * 当前挂起页（TalkPage）登记期望事件；事件（mode/type，selection 本层暂不消费）到达时
 * 转交页对象 resolve/reject。
 * 续体是微任务，GraalJS 在宿主调用返回点排空微任务队列——续体不出 dispatch 所在的
 * strand 任务，CharacterScriptRunner 的 strand 串行契约因此保持。
 *
 * 事件词汇（gms083 实测，见 work/ref/quest-script.log）：mode 1 = 下一步/接受；
 * 0 = 上一步/拒绝；-1（0xFF）= ESC 关闭。单按钮页（仅下一步 00 01、仅上一步 01 00）的
 * 按钮点击同样发 mode 1——"仅上一步"末页因此能推进瀑布（原版 sendPrev 页的收尾衔接）。
 * type 是客户端回显的当前对话框类型（0 = talk，0x0C = accept/decline），用于对口分发。
 *
 * <p><b>类型</b>：JSDoc 类型标注（TypeScript 语法）——GraalJS 运行时忽略，仅供
 * tsc --checkJs / IDE 校验（本文件可用
 * {@code tsc --allowJs --checkJs --noEmit --strict --target es2022 --module es2022} 检查；
 * bind_player 的 API 类型在同目录 bind_player.d.ts）。
 */

import { player, getScriptStrings } from "./bind_player.js";

/** @typedef {import("./bind_player.js").DialogButtonsName} DialogButtonsName */
/** @typedef {import("./bind_player.js").InteractContext} InteractContext */

const EXP_KEY = "$SCRIPTSTRING_QUEST_TEXT_1$";
const i18nFiles = new Map();

/**
 * 官方脚本文本的字面取用视图（纯 JS wrapper，key 格式解释在此，基础取表归 bind_player.js 的
 * getScriptStrings）：i18n.quest0[5] = quest0.xml 的 "$SCRIPTSTRING_QUEST0_5$"。
 * 两层全惰性——第一层（文件名）只建代理不触数据（模块装载期安全），第二层首次索引访问
 * 才装表（per-file 解析一次，序号来自键尾 _N$，_MOBILE 等变体键自然不匹配）。
 * 文件/键缺失抛错；非数字键抛错（typo 响亮失败）；symbol 探测返回 undefined。
 * 保留字 i18n.EXP = quest0 的 SCRIPTSTRING_QUEST_TEXT_1（"经验值"，任务奖励尾巴拼装）。
 *
 * @type {Record<string, string[]> & { EXP: string }}
 */
export const i18n = new Proxy(/** @type {any} */ ({}), {
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
            /** @type {Map<number, string> | null} */
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
 * 翻页事件符号：翻页页 promise() 的落地值（mode 1 = NEXT，mode 0 = PREV）。
 * sendPages 循环据此推进/回退；单页形态（sendNext）见到 PREV 即响亮失败，
 * sendAcceptDecline 只落 true/false，不产生符号。
 *
 * @type {unique symbol}
 */
const PREV = Symbol("prev");
/**
 * 翻页事件符号（"下一步/接受"方向）。
 *
 * @type {unique symbol}
 */
const NEXT = Symbol("next");

/** accept/decline 对话框的 gms083 类型字节（客户端在 NPC_TALK_MORE 回显） */
const ACCEPT_DECLINE = 0x0C;

/**
 * 翻页事件符号对。
 *
 * @typedef {typeof PREV | typeof NEXT} PageTurn
 */

/**
 * 翻页页 promise() 的落地值。
 *
 * @typedef {boolean | PageTurn} PageResolution
 */

/**
 * interact 对象（瀑布函数首参）的脚本面。
 *
 * @typedef {Object} InteractApi
 * @property {(pages: string[], buttons: "PREV_NEXT" | "PREV_OK") => Promise<void>} sendPages
 * @property {(text: string) => Promise<void>} sendNext
 * @property {(text: string) => Promise<boolean>} sendAcceptDecline
 * @property {(path: string) => void} showInfo
 */

/**
 * Promise 提前拆解：resolve/reject 句柄与 promise 分离持有（TalkPage 在事件到达时才
 * 回调句柄）。settled 后再 resolve/reject 为静默 no-op——重复事件/迟到的 ESC 天然幂等
 * （原实现"#pending 置 null 后事件忽略"语义的等价物）。
 *
 * @template T
 */
class Deferred {
    /** @type {{ resolve?: (value: T) => void, reject?: (reason?: Error) => void }} */
    #handles = {};
    /** @type {Promise<T>} */
    #promise = new Promise((resolve, reject) => {
        this.#handles.resolve = resolve;
        this.#handles.reject = reject;
    });

    /** @returns {Promise<T>} */
    get promise() {
        return this.#promise;
    }

    /** @param {T} value */
    resolve(value) {
        this.#handles.resolve?.(value);
    }

    /** @param {Error} error */
    reject(error) {
        this.#handles.reject?.(error);
    }
}

/**
 * 单个对话页（一次性）：text + buttons + 发送与事件配对。resolve 接收入口事件参数，
 * 按页型语义落到 promise：确认页（ACCEPT_DECLINE）resolve true/false；翻页页 resolve
 * NEXT/PREV symbol；ESC（mode -1）reject DialogClosed。type 不对口的事件忽略（客户端
 * 回显的当前对话框类型才可信）；settled 后的后续事件经 Deferred 幂等吞掉。
 */
class TalkPage {
    /** @type {InteractContext} */
    #ctx;
    /** @type {string} */
    #text;
    /** @type {DialogButtonsName} */
    #buttons;
    /** @type {boolean} */
    #isConfirm;
    /** @type {number} */
    #expectType;
    /** @type {Deferred<PageResolution>} */
    #deferred = new Deferred();

    /**
     * @param {InteractContext} ctx
     * @param {string} text
     * @param {DialogButtonsName} buttons
     */
    constructor(ctx, text, buttons) {
        this.#ctx = ctx;
        this.#text = text;
        this.#buttons = buttons;
        this.#isConfirm = buttons === "ACCEPT_DECLINE";
        this.#expectType = this.#isConfirm ? ACCEPT_DECLINE : 0;
    }

    /** 渲染本页（player.talk.send；npc 归会话上下文）。 */
    send() {
        player.talk.send(this.#ctx, this.#text, this.#buttons);
    }

    /**
     * 入口事件落点（InteractionManager.entry 转发；selection 本层暂不消费）。
     *
     * @param {number} mode
     * @param {number} type
     * @param {number} selection
     * @returns {void}
     */
    resolve(mode, type, selection) {
        if (type !== this.#expectType) {
            return;
        }
        if (mode === -1) {
            this.#deferred.reject(new DialogClosed());
        } else if (this.#isConfirm) {
            this.#deferred.resolve(mode === 1);
        } else if (mode === 1) {
            this.#deferred.resolve(NEXT);
        } else if (mode === 0) {
            this.#deferred.resolve(PREV);
        }
    }

    /** @returns {Promise<PageResolution>} */
    promise() {
        return this.#deferred.promise;
    }
}

export class InteractionManager {

    /** @type {(interact: InteractApi, questId: number, npcId: number) => Promise<void>} */
    #startFn;          // 会话瀑布函数
    /** @type {number} */
    #npc;              // 对话 npc（脚本提供；开场一致性断言用）
    /** @type {InteractContext | null} */
    #ctx = null;       // 当前会话身份；实例变化 = 新会话开场
    /** @type {TalkPage | null} */
    #currentPage = null;  // 当前挂起页；null = 无对话——"上一对话尚未结束"判据

    /**
     * @param {(interact: InteractApi, questId: number, npcId: number) => Promise<void>} startFn
     * @param {number} npc
     */
    constructor(startFn, npc) {
        this.#startFn = startFn;
        this.#npc = npc;
    }

    /**
     * ESM 入口（QuestScript 首入与 NPC_TALK_MORE 重入共用，(questId, mode, type,
     * selection, ctx) 原样——questId 由调用显式传递，不经会话取）。
     * 首入 (1, 0, 0) 与"下一步"事件同形，无法从事件本身区分——以会话身份判别：
     * InteractContext 实例变化 → 新会话开场（跑瀑布函数，完成/被关闭后自动 dispose）；
     * 同会话 → 事件转交当前挂起页（无挂起页则忽略）。
     *
     * @type {(questId: number, mode: number, type: number, selection: number, ctx: InteractContext) => void}
     */
    entry = (questId, mode, type, selection, ctx) => {
        if (ctx !== this.#ctx) {
            this.#begin(ctx, questId);
        } else {
            this.#currentPage?.resolve(mode, type, selection);
        }
    };

    /**
     * @param {InteractContext} ctx
     * @param {number} questId
     * @returns {void}
     */
    #begin(ctx, questId) {
        // 脚本提供的 npc 与会话 npc 不一致 = 脚本 typo（对话会发往错误的 npc token），
        // 响亮失败（typo 响亮失败纪律，同 i18n）
        if (ctx.getNpcId() !== this.#npc) {
            throw new Error("interaction: npc 不匹配（脚本 " + this.#npc + " / 会话 " + ctx.getNpcId() + "）");
        }
        this.#ctx = ctx;
        this.#currentPage = null;
        // 瀑布终结（正常 return / DialogClosed / 脚本异常 reject）即 dispose——脚本 reset
        // 钩子 + 会话清除 + NPC 冷却。DialogClosed（ESC）是正常用户行为，静默收尾；
        // 其余异常重新抛出——不被本层吞掉。吞掉即静默死：微任务续体的 rejection 成
        // 悬空无处理者，宿主/strand 均无感知（strict canary 排查实证）；重抛的 rejection
        // 由 context 的 js.unhandled-rejections=throw 在微任务排空点转 PolyglotException
        // 出宿主调用，落 CharacterScriptRunner 的 fail-safe ERROR 日志。
        this.#startFn(this.#interact(ctx), questId, ctx.getNpcId()).then(
            () => ctx.dispose(),
            (e) => {
                ctx.dispose();
                if (!(e instanceof DialogClosed)) {
                    throw e;
                }
            },
        );
    }

    /**
     * @param {InteractContext} ctx
     * @returns {InteractApi}
     */
    #interact(ctx) {
        const self = this;
        return {
            /** "上一步/下一步"连播链：整链一个 await，链内回退不跨越 await（脚本零状态）。
             *  buttons = 末页样式；非末页样式由位置推导（首页 NEXT / 中间 PREV_NEXT）。
             *
             *  @param {string[]} pages
             *  @param {"PREV_NEXT" | "PREV_OK"} lastButtons
             *  @returns {Promise<void>} */
            async sendPages(pages, lastButtons) {
                if (!Array.isArray(pages) || pages.length === 0 || pages.some((p) => typeof p !== "string")) {
                    throw new Error("interaction: pages 须为非空字符串数组");
                }
                if (lastButtons !== "PREV_OK" && lastButtons !== "PREV_NEXT") {
                    throw new Error("interaction: 末页 buttons 非法: " + lastButtons);
                }
                const talks = pages.map((text, i) => new TalkPage(
                    ctx,
                    text,
                    i === pages.length - 1 ? lastButtons : (i === 0 ? "NEXT" : "PREV_NEXT"),
                ));
                let index = 0;
                while (true) {
                    const ev = await self.#show(talks[index]);
                    if (ev === PREV) {
                        // 首页无上一步按钮，PREV 理论不可达；防御性夹回首页（同页重渲染一次）
                        index = Math.max(0, index - 1);
                    } else if (index === talks.length - 1) {
                        return;   // 末页 NEXT → 整链 resolve
                    } else {
                        index += 1;
                    }
                }
            },
            /**
             * 单页"下一步"（00 01）；按下一步 resolve（返回 undefined）。
             *
             * @param {string} text
             * @returns {Promise<void>}
             */
            async sendNext(text) {
                const ev = await self.#show(new TalkPage(ctx, text, "NEXT"));
                if (ev !== undefined) {
                    throw new Error("interaction: 单页对话收到多按钮事件: " + String(ev));
                }
            },
            /**
             * 接受/拒绝：接受 → true（mode 1），拒绝 → false（mode 0），ESC → reject。
             *
             * @param {string} text
             * @returns {Promise<boolean>}
             */
            async sendAcceptDecline(text) {
                const ev = await self.#show(new TalkPage(ctx, text, "ACCEPT_DECLINE"));
                if (typeof ev !== "boolean") {
                    throw new Error("interaction: 确认页收到翻页事件: " + String(ev));
                }
                return ev;
            },
            /** 过场 UI 图（透传会话 API）。
             *
             *  @param {string} path */
            showInfo(path) {
                ctx.showInfo(path);
            },
        };
    }

    /**
     * 发送单页并等待其事件：登记 #currentPage（"上一对话尚未结束"判据与事件路由锚点）、
     * 渲染、挂起。落地值翻译：PREV = 本页无上一步按钮却收到上一步（协议异常，响亮失败）；
     * NEXT → undefined；其余原样返回（确认页 true/false）。
     *
     * @param {TalkPage} page
     * @returns {Promise<PageResolution | undefined>}
     */
    async #show(page) {
        if (this.#currentPage !== null) {
            throw new Error("interaction: 上一对话尚未结束");
        }
        this.#currentPage = page;
        page.send();
        const ev = await page.promise();
        this.#currentPage = null;
        if (ev === PREV) {
            throw new Error("interaction: 对话页收到上一步事件（本页无上一步按钮）");
        }
        return ev === NEXT ? undefined : ev;
    }
}
