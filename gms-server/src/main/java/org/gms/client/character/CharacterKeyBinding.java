package org.gms.client.character;

import org.gms.client.keybind.KeyBinding;
import org.gms.client.keybind.QuickslotBinding;
import org.gms.config.GameConfig;
import org.gms.constants.game.GameConstants;
import org.gms.util.PacketCreator;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 键位绑定模块组件：键位映射（keymap = 按键 → KeyBinding）+ 快捷栏（QuickslotBinding）。
 * 仿照 CharacterBuffs/CharacterChair 模式：数据 + 领域逻辑内聚于此，持有 owner 反向引用，
 * Character 保留公开具名门面（getKeymap/changeKeybinding/sendKeymap/... 对外转发）。
 *
 * 边界：只承载键位绑定语义——keymap（KeyBinding 集合）与 quickslot 快捷栏。
 * 持久化 SQL（keymap/quickslotkeymapped 表）留在 Character.saveCharToDB；
 * 依赖经 owner 门面调用（sendPacket/...）。
 */
class CharacterKeyBinding {
    private final Character owner;

    /** 键位映射：按键码 → KeyBinding（keymap 即 keybinding 的集合容器） */
    private final Map<Integer, KeyBinding> keymap = new LinkedHashMap<>();

    /** 上次保存的快捷栏字节（用于变更检测） */
    private byte[] quickSlotLoaded;

    /** 当前快捷栏绑定 */
    private QuickslotBinding quickSlotKeyMapped;

    CharacterKeyBinding(Character owner) {
        this.owner = owner;

        // Select a keybinding method
        boolean useCustomKeySet = GameConfig.getServerBoolean("use_custom_keyset");
        int[] selectedKey = GameConstants.getCustomKey(useCustomKeySet);
        int[] selectedType = GameConstants.getCustomType(useCustomKeySet);
        int[] selectedAction = GameConstants.getCustomAction(useCustomKeySet);

        for (int i = 0; i < selectedKey.length; i++) {
            keymap.put(selectedKey[i], new KeyBinding(selectedType[i], selectedAction[i]));
        }
    }

    // ── 查询 ──

    Map<Integer, KeyBinding> getKeymap() {
        return keymap;
    }

    byte[] getQuickSlotLoaded() {
        return quickSlotLoaded;
    }

    void setQuickSlotLoaded(byte[] quickSlotLoaded) {
        this.quickSlotLoaded = quickSlotLoaded;
    }

    QuickslotBinding getQuickSlotKeyMapped() {
        return quickSlotKeyMapped;
    }

    void setQuickSlotKeyMapped(QuickslotBinding quickSlotKeyMapped) {
        this.quickSlotKeyMapped = quickSlotKeyMapped;
    }

    // ── 变更 ──

    void changeKeybinding(int key, KeyBinding keybinding) {
        if (keybinding.getType() != 0) {
            keymap.put(key, keybinding);
        } else {
            keymap.remove(key);
        }
    }

    void changeQuickslotKeybinding(byte[] aQuickslotKeyMapped) {
        this.quickSlotKeyMapped = new QuickslotBinding(aQuickslotKeyMapped);
    }

    // ── 发送 ──

    void sendKeymap() {
        owner.sendPacket(PacketCreator.getKeymap(keymap));
    }

    void sendQuickmap() {
        // send quickslots to user
        QuickslotBinding pQuickslotKeyMapped = this.quickSlotKeyMapped;

        if (pQuickslotKeyMapped == null) {
            pQuickslotKeyMapped = new QuickslotBinding(QuickslotBinding.DEFAULT_QUICKSLOTS);
        }

        owner.sendPacket(PacketCreator.QuickslotMappedInit(pQuickslotKeyMapped));
    }
}
