package org.gms.scripting.item;

import org.gms.client.character.Character;
import org.gms.client.inventory.InventoryTab;
import org.gms.client.inventory.Item;
import org.gms.client.inventory.ItemSlot;
import org.gms.client.inventory.ItemDefinition;
import org.gms.client.inventory.ItemRegistry;
import org.gms.scripting.JsModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * 道具钩子脚本的 Java 包装：把 ItemDefinition.hooks 指定的 ESM 包成可调对象
 * （见 doc/10）。
 *
 * <ul>
 *   <li>惰性装载：首次 {@link #hasHook}/{@link #invoke} 才经角色宿主装载模块
 *       （{@code runner.moduleFor}，per-client）；文件缺失 → dead（warn 一次），
 *       此后全部按无脚本处理——道具构造（含 DB 装载）零脚本 IO；</li>
 *   <li>失败降级：钩子内脚本异常记日志返回 null（fail-safe）；重算路径从真实状态重建，
 *       无半成品状态；</li>
 *   <li>线程模型（M1.5）：钩子在 owner 的 strand 上同步执行——enter/leave 是状态迁移的
 *       后半段（道具↔宠物等耦合在此建立/拆除），调用方必须等待完成（不得观察到半Applied
 *       状态）；InventoryTransaction 在释放背包锁后派发。模块状态作用域为 per-client
 *       （原全局共享语义退役，见 JsModule）。</li>
 * </ul>
 */
public final class ItemScript {

    public static final String HOOK_ENTER = "onEnterInventory";
    public static final String HOOK_LEAVE = "onLeaveInventory";
    public static final String HOOK_EQUIP = "onEquip";
    public static final String HOOK_UNEQUIP = "onUnequip";
    public static final String HOOK_USE = "onUse";
    // 未来扩展：同一脚本文件加导出 + 此处加常量 + 调用点

    private static final Logger log = LoggerFactory.getLogger(ItemScript.class);
    private static final Map<String, ItemScript> CACHE = new HashMap<>();

    /**
     * 容器事件的唯一派发入口：入包（isLogin=true = 登录装载初始化）。
     * 同步执行于 owner strand（ strand 内调用 inline；跨线程调用阻塞等待完成）。
     */
    public static void postEnter(Character chr, Item item, boolean isLogin) {
        chr.getScriptRunner().run(() -> invokeEnter(chr, item, isLogin));
    }

    /** 容器事件的唯一派发入口：出包（isLogout=true = 登出清场） */
    public static void postLeave(Character chr, Item item, boolean isLogout) {
        chr.getScriptRunner().run(() -> invokeLeave(chr, item, isLogout));
    }

    /** 同步执行进入钩子（由 CharacterScriptRunner 调用；无定义/无钩子静默） */
    public static void invokeEnter(Character chr, Item item, boolean isLogin) {
        ItemScript s = forItem(item.getItemId());
        if (s != null) {
            s.invoke(chr, HOOK_ENTER, chr, item, isLogin);
        }
    }

    /** 同步执行离开钩子 */
    public static void invokeLeave(Character chr, Item item, boolean isLogout) {
        ItemScript s = forItem(item.getItemId());
        if (s != null) {
            s.invoke(chr, HOOK_LEAVE, chr, item, isLogout);
        }
    }

    /** 装备穿戴事件的唯一派发入口（isLogin=true = 登录装载初始化） */
    public static void postEquip(Character chr, Item item, boolean isLogin) {
        chr.getScriptRunner().run(() -> invokeEquip(chr, item, isLogin));
    }

    /** 装备卸下事件的唯一派发入口（isLogout=true = 登出清场，当前引擎不产生，契约保留） */
    public static void postUnequip(Character chr, Item item, boolean isLogout) {
        chr.getScriptRunner().run(() -> invokeUnequip(chr, item, isLogout));
    }

    /** 同步执行穿戴钩子 */
    public static void invokeEquip(Character chr, Item item, boolean isLogin) {
        ItemScript s = forItem(item.getItemId());
        if (s != null) {
            s.invoke(chr, HOOK_EQUIP, chr, item, isLogin);
        }
    }

    /** 同步执行卸下钩子 */
    public static void invokeUnequip(Character chr, Item item, boolean isLogout) {
        ItemScript s = forItem(item.getItemId());
        if (s != null) {
            s.invoke(chr, HOOK_UNEQUIP, chr, item, isLogout);
        }
    }

    /**
     * 使用钩子同步执行。调用方先用 {@link #hasHook}(HOOK_USE) 判定"有 onUse 钩子"再调本方法
     * （首次判定会触发脚本装载）；执行经 CharacterScriptRunner.call（strand 上，等待结论）。
     * 返回 true = 允许使用（消耗由调用方统一执行）；脚本返回 false/非布尔/执行异常 → false
     * （拒绝，fail-safe 不消耗）。
     */
    public boolean invokeUse(Character chr, Item item) {
        Object ret = chr.getScriptRunner().call(() -> invoke(chr, HOOK_USE, chr, item));
        return ret instanceof Boolean b && b;
    }

    /** 登出清场：全部在包道具补发 leave(isLogout=true)（脚本清理定时器与条目），随后关闭脚本宿主 */
    public static void logout(Character chr) {
        for (InventoryTab tab : chr.getInventory().tabs) {
            for (ItemSlot item : tab.list()) {
                chr.getScriptRunner().run(() -> invokeLeave(chr, item.getItem(), true));
            }
        }
        chr.getScriptRunner().close();
    }

    private final String path;
    private volatile boolean dead;

    private ItemScript(String path) {
        this.path = path;
    }

    /** 按 ItemDefinition.hooks 取包装；未登记道具返回 null */
    public static ItemScript forItem(int itemId) {
        ItemDefinition def = ItemRegistry.of(itemId);
        return def == null ? null : of(def.hooks());
    }

    /** 取路径对应的包装实例（intern，不触盘）；装载推迟到首次使用 */
    public static ItemScript of(String path) {
        synchronized (CACHE) {
            return CACHE.computeIfAbsent(path, ItemScript::new);
        }
    }

    /** 脚本是否导出该钩子（首次调用触发装载；文件缺失/导出缺失/已 dead → false） */
    public boolean hasHook(Character chr, String hook) {
        JsModule m = module(chr);
        return m != null && m.get(hook) != null;
    }

    /** 调用钩子；无钩子/脚本异常 → null（fail-safe）。在 owner strand 上执行（context 内无并行）。 */
    public Object invoke(Character chr, String hook, Object... args) {
        JsModule m = module(chr);
        if (m == null) {
            return null;
        }
        try {
            return m.callExport(hook, args);
        } catch (RuntimeException e) {
            log.error("道具钩子脚本执行异常: {}#{}", path, hook, e);
            return null;
        }
    }

    /** 模块解析经角色宿主（per-client）；装载失败 → dead（warn 一次） */
    private JsModule module(Character chr) {
        if (dead) {
            return null;
        }
        try {
            return chr.getScriptRunner().moduleFor(path);
        } catch (RuntimeException e) {
            dead = true;
            log.warn("道具钩子脚本装载失败，此后按无脚本处理: {}", path, e);
            return null;
        }
    }
}
