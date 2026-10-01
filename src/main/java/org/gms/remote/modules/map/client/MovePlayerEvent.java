package org.gms.remote.modules.map.client;

import org.gms.remote.ClientEvent;
import org.gms.remote.Module;
import org.gms.remote.modules.map.client.movement.MoveElement;

import java.util.List;

/** MOVE_PLAYER 语义事件：本端玩家移动元素序列（应用与广播编排在地图域）。 */
public record MovePlayerEvent(List<MoveElement> elements) implements ClientEvent {

    public MovePlayerEvent {
        elements = List.copyOf(elements);
    }

    @Override
    public Module module() {
        return Module.MAP;
    }
}
