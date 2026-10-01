package org.gms.remote.gms083.client.translate;

import org.gms.remote.ClientEvent;
import org.gms.remote.modules.map.client.MoveLife;

/**
 * MOVE_LIFE 翻译：恒等——decode 产物 {@link MoveLife} 即语义事件本体（无再翻译）。
 */
public final class MoveLifeTranslator implements InTranslator<MoveLife> {

    @Override
    public ClientEvent translate(MoveLife life) {
        return life;
    }
}
