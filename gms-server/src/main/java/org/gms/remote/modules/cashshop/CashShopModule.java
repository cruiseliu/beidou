package org.gms.remote.modules.cashshop;

import org.gms.remote.AbstractModule;

/**
 * 语义模块：商城域（C→S 首个入口；S→C API 随商城迁移补充）。
 */
public abstract class CashShopModule extends AbstractModule {

    /** 商城返回语义入口（CHANGE_MAP 空载荷形态；频道路由由版本实现负责）。 */
    public interface Handler {
        void leaveCashShop();
    }
}
