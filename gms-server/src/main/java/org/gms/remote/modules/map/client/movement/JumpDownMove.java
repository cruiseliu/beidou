package org.gms.remote.modules.map.client.movement;

/** 跳下（command 15）。 */
public record JumpDownMove(int command, int x, int y, int ppsX, int ppsY, int fh, int originFh, int stance, int duration) implements MoveElement {
}
