package org.gms.remote.modules.map.client.movement;

/** 绝对移动（command 0/5/17）。 */
public record AbsoluteMove(int command, int x, int y, int ppsX, int ppsY, int fh, int stance, int duration) implements MoveElement {
}
