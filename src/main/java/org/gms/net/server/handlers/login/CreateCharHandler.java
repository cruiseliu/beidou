/*
 This file is part of the OdinMS Maple Story Server
 Copyright (C) 2008 Patrick Huy <patrick.huy@frz.cc>
 Matthias Butz <matze@odinms.de>
 Jan Christian Meyer <vimes@odinms.de>

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
package org.gms.net.server.handlers.login;

import lombok.extern.slf4j.Slf4j;
import org.gms.client.Client;
import org.gms.client.creator.CharacterFactory;
import org.gms.client.creator.CharacterTemplate;
import org.gms.client.creator.CharacterTemplateRegistry;
import org.gms.net.AbstractPacketHandler;
import org.gms.net.packet.InPacket;
import org.gms.util.I18nUtil;
import org.gms.util.PacketCreator;

@Slf4j
public final class CreateCharHandler extends AbstractPacketHandler {

    @Override
    public boolean queued() {
        return true;   // strand 迁移 M2-2a：建角与 charlist 共用 addCharEntry 展示路径（登录族）
    }


    @Override
    public void handlePacket(InPacket p, Client c) {
        String name = p.readString();
        int job = p.readInt();
        int face = p.readInt();

        int hair = p.readInt();
        int hairColor = p.readInt();
        int skinColor = p.readInt();

        int top = p.readInt();
        int bottom = p.readInt();
        int shoes = p.readInt();
        int weapon = p.readInt();
        int gender = p.readByte();

        int status;
        // 职业路由数据化（doc/14）：客户端职业码 → CharacterTemplate（novice 标志 = 可建开关，
        // 原 GameConfig enable_* 职业开关废弃）；外观校验合并进 CharacterFactory（模板候选集唯一门）
        CharacterTemplate template = CharacterTemplateRegistry.byNoviceJobCode(job);
        if (template == null) {
            c.sendPacket(PacketCreator.deleteCharResponse(0, 9));
            return;
        }
        if (!template.novice()) {
            // 模板 novice=false = 该职业群禁用创建
            String jobname = I18nUtil.getMessage("CreateCharHandler.handlePacket.job." + job);
            String message = I18nUtil.getMessage("CreateCharHandler.handlePacket.serverNotice.disableJob", jobname);
            c.sendPacket(PacketCreator.serverNotice(1, message));    //由于未找到不弹窗结束客户端请求等待，所以先发出未知错误的提示，再发送弹窗提示，这样不会被未知错误窗口挡住
            c.sendPacket(PacketCreator.getLoginFailed(1));       //断开客户端请求，避免客户端假死
            return;
        }

        status = CharacterFactory.createNewCharacter(c, name, gender,
                new CharacterFactory.NewCharacterAppearance(face, hair + hairColor, skinColor, top, bottom, shoes, weapon), template, 0);

        if (status != 0) {
            c.sendPacket(PacketCreator.deleteCharResponse(0, 9));       //发送未知错误的弹窗提示
        }
    }
}