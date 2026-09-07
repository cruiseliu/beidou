package org.gms.remote.in;

import org.gms.remote.ModuleIn;
import org.gms.remote.in.events.ClientEvent;
import org.gms.remote.in.events.SummonPetEvent;
import org.gms.remote.in.events.UseItemEvent;

/**
 * ClientEvent → 模块 In 入口的分派表（语义知识：哪个事件归哪个模块，随 in-op 迁移增长）。
 * 与 out 侧 deliver 的不对称是正确的：事件→模块入口是语义知识（语义层持有），
 * 事件→编码映射是版本知识（v83.out 持有）。
 */
public final class ModuleInDispatch {

    private ModuleInDispatch() {
    }

    /** exhaustive switch（ClientEvent 封闭）：新增事件即编译期强制补分派 */
    public static void dispatch(ClientEvent e, ModuleIn in) {
        switch (e) {
            case SummonPetEvent x -> in.pet().summonPet(x);
            case UseItemEvent x -> in.inventory().useItem(x);
        }
    }
}
