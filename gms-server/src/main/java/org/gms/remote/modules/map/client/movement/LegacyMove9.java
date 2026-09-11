package org.gms.remote.modules.map.client.movement;

/** 保留布局（command 14，wire 9 字节=2+2+2+1+2，字段边界未做语义解释）。 */
public record LegacyMove9(int command, int f1, int f2, int f3, int f4, int f5) implements MoveElement {
}
