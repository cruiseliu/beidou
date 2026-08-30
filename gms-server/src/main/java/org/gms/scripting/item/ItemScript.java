package org.gms.scripting.item;

import org.gms.client.character.Character;
import org.gms.client.inventory.InventoryTab;
import org.gms.client.inventory.Item;
import org.gms.client.inventory.ItemSlot;
import org.gms.client.inventory.ItemDefinition;
import org.gms.client.inventory.ItemRegistry;
import org.gms.scripting.JsModule;
import org.graalvm.polyglot.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * 道具钩子脚本的 Java 包装：把 ItemDefinition.hooks 指定的 ESM 包成可调对象
 * （JsModule 既有模式，见 doc/10）。
 *
 * <ul>
 *   <li>惰性装载：首次 {@link #hasHook}/{@link #invoke} 才 importModule；文件缺失 →
 *       dead（warn 一次），此后全部按无脚本处理——道具构造（含 DB 装载）零脚本 IO；</li>
 *   <li>失败降级：钩子内脚本异常记日志返回 null（fail-safe）；重算路径从真实状态重建，
 *       无半成品状态；</li>
 *   <li>同一路径全局共享同一实例（intern）——脚本状态天然跨角色可见（有状态脚本契约，
 *       coupon.js 的 pendingTimers 依赖此语义）；</li>
 *   <li>线程模型：钩子执行须在调用方的脚本会话内（CharacterScriptRunner 保证串行；
 *       脚本内 setTimeout 依赖会话上下文），JsModule 内部 synchronized 兜底互斥。</li>
 * </ul>
 */
public final class ItemScript {

    public static final String HOOK_ENTER = "onEnterInventory";
    public static final String HOOK_LEAVE = "onLeaveInventory";
    public static final String HOOK_EQUIP = "onEquip";
    public static final String HOOK_UNEQUIP = "onUnequip";
    // 未来扩展：同一脚本文件加导出 + 此处加常量 + 调用点

    private static final Logger log = LoggerFactory.getLogger(ItemScript.class);
    private static final Map<String, ItemScript> CACHE = new HashMap<>();

    /**
     * 容器事件的唯一派发入口：入包（isLogin=true = 登录装载初始化）。
     * 经角色调度器异步串行执行——容器只需调本方法，不感知调度细节。
     */
    public static void postEnter(Character chr, Item item, boolean isLogin) {
        chr.getScriptRunner().post(() -> invokeEnter(chr, item, isLogin));
    }

    /** 容器事件的唯一派发入口：出包（isLogout=true = 登出清场） */
    public static void postLeave(Character chr, Item item, boolean isLogout) {
        chr.getScriptRunner().post(() -> invokeLeave(chr, item, isLogout));
    }

    /** 调度器会话内同步执行进入钩子（由 CharacterScriptRunner 调用；无定义/无钩子静默） */
    public static void invokeEnter(Character chr, Item item, boolean isLogin) {
        ItemScript s = forItem(item.getItemId());
        if (s != null) {
            s.invoke(HOOK_ENTER, chr, item, isLogin);
        }
    }

    /** 调度器会话内同步执行离开钩子 */
    public static void invokeLeave(Character chr, Item item, boolean isLogout) {
        ItemScript s = forItem(item.getItemId());
        if (s != null) {
            s.invoke(HOOK_LEAVE, chr, item, isLogout);
        }
    }

    /** 装备穿戴事件的唯一派发入口（isLogin=true = 登录装载初始化） */
    public static void postEquip(Character chr, Item item, boolean isLogin) {
        chr.getScriptRunner().post(() -> invokeEquip(chr, item, isLogin));
    }

    /** 装备卸下事件的唯一派发入口（isLogout=true = 登出清场，当前引擎不产生，契约保留） */
    public static void postUnequip(Character chr, Item item, boolean isLogout) {
        chr.getScriptRunner().post(() -> invokeUnequip(chr, item, isLogout));
    }

    /** 调度器会话内同步执行穿戴钩子 */
    public static void invokeEquip(Character chr, Item item, boolean isLogin) {
        ItemScript s = forItem(item.getItemId());
        if (s != null) {
            s.invoke(HOOK_EQUIP, chr, item, isLogin);
        }
    }

    /** 调度器会话内同步执行卸下钩子 */
    public static void invokeUnequip(Character chr, Item item, boolean isLogout) {
        ItemScript s = forItem(item.getItemId());
        if (s != null) {
            s.invoke(HOOK_UNEQUIP, chr, item, isLogout);
        }
    }

    /** 登出清场：全部在包道具补发 leave(isLogout=true)（脚本清理定时器与条目），随后关闭脚本会话 */
    public static void logout(Character chr) {
        for (InventoryTab tab : chr.getInventory().tabs) {
            for (ItemSlot item : tab.list()) {
                chr.getScriptRunner().post(() -> invokeLeave(chr, item.getItem(), true));
            }
        }
        chr.getScriptRunner().close();
    }

    private final String path;
    private volatile JsModule module;
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
    public boolean hasHook(String hook) {
        JsModule m = module();
        return m != null && m.get(hook) != null;
    }

    /** 调用钩子；无钩子/脚本异常 → null（fail-safe）。执行在模块锁内（context 内无并行）。 */
    public Object invoke(String hook, Object... args) {
        JsModule m = module();
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

    private synchronized JsModule module() {
        if (module == null && !dead) {
            try {
                module = JsModule.importModule(path);
            } catch (RuntimeException e) {
                dead = true;
                log.warn("道具钩子脚本装载失败，此后按无脚本处理: {}", path, e);
            }
        }
        return module;
    }
}
