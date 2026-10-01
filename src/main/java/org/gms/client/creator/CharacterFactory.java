/*
    This file is part of the HeavenMS MapleStory Server
    Copyleft (L) 2016 - 2019 RonanLana

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as
    published by the Free Software Foundation version 3 as published by
    the Free Software Foundation. You may not use, modify or distribute
    this program under any other version of the GNU Affero General Public
    License.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/
package org.gms.client.creator;

import org.gms.client.character.Character;
import org.gms.client.Client;
import org.gms.client.SkinColor;
import org.gms.client.inventory.InventoryTab;
import org.gms.client.inventory.InventoryType;
import org.gms.client.inventory.ItemSlot;
import org.gms.config.GameConfig;
import org.gms.net.server.Server;
import org.gms.util.I18nUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.gms.server.ItemInformationProvider;
import org.gms.util.PacketCreator;

/**
 * 角色创建服务：{@link CharacterTemplate}（data/character_template/*.json，唯一数据真相）
 * + 本类的纯逻辑（校验/装配/持久化编排），doc/14。
 *
 * <p>外观校验为唯一一道门：提交参数 ∈ 模板候选集（Etc.wz/MakeCharInfo.img，路径随模板）。
 * novice 提交全量 8 项外观并穿戴；veteran（老兵卡）外观仅 4 项、装备由模板携带
 * （improveSp 为客户端提交的强化档位，修正规则在模板 mapleLifeEnhance 数据上执行）。
 */
public final class CharacterFactory {
    private static final Logger log = LoggerFactory.getLogger(CharacterFactory.class);

    /** 创建提交的外观参数（novice：8 项全量；veteran：top/bottom/shoes/weapon 恒 0，装备由模板携带） */
    public record NewCharacterAppearance(int face, int hair, int skin, int top, int bottom, int shoes, int weapon) {
    }

    public synchronized static int createNewCharacter(Client c, String name, int gender, NewCharacterAppearance app,
                                                      CharacterTemplate template, int improveSp) {
        if (GameConfig.getServerBoolean("collective_chr_slot") ? c.getAvailableCharacterSlots() <= 0 : c.getAvailableCharacterWorldSlots() <= 0) {
            return -3;
        }

        if (!Character.canCreateChar(name)) {
            return -1;
        }

        if (!validateAppearance(template, gender, app)) {
            log.warn("Owner from account {} tried to packet edit in character creation", c.getAccountName());
            return -2;
        }

        Character newCharacter = Character.getDefault(c);
        newCharacter.setWorld(c.getWorld());
        newCharacter.setSkinColor(SkinColor.getById(app.skin()));
        newCharacter.setGender(gender);
        newCharacter.setName(name);
        newCharacter.setHair(app.hair());
        newCharacter.setFace(app.face());

        newCharacter.applyCharacterTemplate(template);

        InventoryTab equipped = newCharacter.getInventory(InventoryType.EQUIPPED);
        if (template.novice()) {
            // novice 初始穿戴来自客户端提交；veteran 穿戴位已在模板 baseData.inventory（position<0）
            wear(equipped, app.top(), (byte) -5);
            wear(equipped, app.bottom(), (byte) -6);
            wear(equipped, app.shoes(), (byte) -7);
            wear(equipped, app.weapon(), (byte) -11);
        }

        if (improveSp > 0 && template.mapleLifeEnhance() != null) {
            newCharacter.applyMapleLifeEnhance(template.mapleLifeEnhance(), improveSp);
        }

        if (!newCharacter.insertNewChar()) {
            return -2;
        }
        c.sendPacket(PacketCreator.addNewCharEntry(newCharacter));

        Server.getInstance().createCharacterEntry(newCharacter);
        Server.getInstance().broadcastGMMessage(c.getWorld(), PacketCreator.sendYellowTip("[New Char]: " + c.getAccountName() + I18nUtil.getMessage("CharacterFactory.message1") + name));
        log.info("账号 {} 创建了角色 {}", c.getAccountName(), name);

        return 0;
    }

    private static void wear(InventoryTab equipped, int itemId, byte position) {
        if (itemId > 0) {
            ItemSlot eq = ItemInformationProvider.getInstance().getEquipById(itemId);
            eq.setPosition(position);
            equipped.addItemFromDB(eq);
        }
    }

    /** 外观门（唯一一道，doc/14 §5.1）：提交参数 ∈ 模板候选集；veteran 不校验装备（装备由模板携带） */
    private static boolean validateAppearance(CharacterTemplate template, int gender, NewCharacterAppearance app) {
        String path = template.candidatesFor(gender == 1);
        if (path == null) {
            return false;
        }
        MakeCharInfo info = MakeCharInfo.of(path);
        if (!info.verifyFaceId(app.face())) return false;
        if (!info.verifyHairId(app.hair())) return false;
        if (!info.verifyHairColorId(app.hair())) return false;
        if (!info.verifySkinId(app.skin())) return false;
        if (template.novice()) {
            if (!info.verifyTopId(app.top())) return false;
            if (!info.verifyBottomId(app.bottom())) return false;
            if (!info.verifyShoeId(app.shoes())) return false;
            if (!info.verifyWeaponId(app.weapon())) return false;
        }
        return true;
    }
}
