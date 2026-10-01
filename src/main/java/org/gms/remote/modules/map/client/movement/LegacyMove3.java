package org.gms.remote.modules.map.client.movement;

/** 保留布局（command 21，wire 3 字节=1+1+1，同上）。 */
public record LegacyMove3(int command, int f1, int f2, int f3) implements MoveElement {
}
