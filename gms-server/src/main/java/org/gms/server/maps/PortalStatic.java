package org.gms.server.maps;

import org.gms.provider.Data;
import org.gms.provider.DataTool;

import java.awt.Point;

/**
 * Portal 静态半（Portal 归属 map actor 拆分，第一步）：id/name/type/position/target/
 * targetMapId 均为 WZ 装载期即定的不可变事实，挂 {@link MapleMapStatic} 供 player
 * strand 无锁直读。动态半（scriptName/status/state）归 map actor 内部门禁对象。
 */
public record PortalStatic(int id, String name, int type, Point position, String target, int targetMapId) {

    /** WZ portal 节点装载（字段读取与 PortalFactory.loadPortal 同源） */
    public static PortalStatic of(int id, Data portal) {
        return new PortalStatic(
                id,
                DataTool.getString(portal.getChildByPath("pn")),
                DataTool.getInt(portal.getChildByPath("pt")),
                new Point(DataTool.getInt(portal.getChildByPath("x")), DataTool.getInt(portal.getChildByPath("y"))),
                DataTool.getString(portal.getChildByPath("tn")),
                DataTool.getInt(portal.getChildByPath("tm")));
    }

    /** 活 Portal 快照（legacy 调用方交界处转换；仅读静态字段） */
    public static PortalStatic of(Portal portal) {
        return new PortalStatic(portal.getId(), portal.getName(), portal.getType(),
                portal.getPosition(), portal.getTarget(), portal.getTargetMapId());
    }
}
