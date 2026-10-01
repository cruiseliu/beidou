package org.gms.remote.gms083.client.translate;

import org.gms.remote.ClientEvent;
import org.gms.remote.modules.map.client.MovePlayerEvent;
import org.gms.remote.modules.map.client.movement.MoveElement;

import java.util.List;

/**
 * MOVE_PLAYER 翻译：decode 产出的元素序列包装为语义事件（纯映射，无状态）。
 */
public final class MovePlayerTranslator implements InTranslator<List<MoveElement>> {

    @Override
    public ClientEvent translate(List<MoveElement> elements) {
        return new MovePlayerEvent(elements);
    }
}
