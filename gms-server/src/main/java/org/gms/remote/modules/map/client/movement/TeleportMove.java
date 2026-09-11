package org.gms.remote.modules.map.client.movement;

/** 瞬移/突进（command 3/4/7/8/9）。 */
public record TeleportMove(int command, int x, int y, int ppsX, int ppsY, int stance) implements MoveElement {
}
