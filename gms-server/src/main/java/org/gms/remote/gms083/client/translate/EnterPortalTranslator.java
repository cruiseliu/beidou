package org.gms.remote.gms083.client.translate;

import org.gms.remote.ClientEvent;
import org.gms.remote.modules.map.client.EnterPortalEvent;

/**
 * CHANGE_MAP_SPECIAL 翻译：恒等——decode 产物即语义事件本体。
 */
public final class EnterPortalTranslator implements InTranslator<EnterPortalEvent> {

    @Override
    public ClientEvent translate(EnterPortalEvent event) {
        return event;
    }
}
