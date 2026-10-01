package org.gms.remote.modules.map.client.movement;

/** 椅子（command 11，服务端仅应用姿态）。 */
public record ChairMove(int command, int x, int y, int fh, int stance, int duration) implements MoveElement {
}
