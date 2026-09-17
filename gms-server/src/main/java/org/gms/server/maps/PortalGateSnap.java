package org.gms.server.maps;

/**
 * Portal 动态门禁快照（portal 归属 map actor 拆分）：scriptName/status/state 在入域
 * 时点抽取，player strand 只见快照不持活 Portal。查无门由查询方返回 null（对齐
 * getPortal 语义）。status = 脚本门开启位（事件脚本控制），state = GM 开关门。
 */
public record PortalGateSnap(String scriptName, boolean status, boolean state) {
}
