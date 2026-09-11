package org.gms.remote.modules.map.client.movement;

/** 相对移动（command 1/2/6/12/13/16/18/19/20/22）。 */
public record RelativeMove(int command, int x, int y, int stance, int duration) implements MoveElement {
}
