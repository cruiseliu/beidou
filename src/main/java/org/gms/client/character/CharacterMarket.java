package org.gms.client.character;

import org.gms.client.inventory.ItemSlot;
import org.gms.client.inventory.manipulator.InventoryManipulator;
import org.gms.client.processor.npc.FredrickProcessor;
import org.gms.dao.entity.CharactersDO;
import org.gms.server.maps.HiredMerchant;
import org.gms.server.maps.PlayerShop;
import org.gms.server.maps.PlayerShopItem;
import org.gms.util.DatabaseConnection;
import org.gms.util.I18nUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * 商店模块组件：雇佣商店（HiredMerchant）+ 个人商店（PlayerShop）+ 商店 meso 账目。
 * 仿照 CharacterBuffs/CharacterChair 模式：数据 + 领域逻辑内聚于此，持有 owner 反向引用，
 * Character 保留公开具名门面（getPlayerShop/getHiredMerchant/... 对外转发）。
 *
 * 边界：只承载商店语义——雇佣商店/个人商店实例与关闭、商店 meso（merchantmeso/hasMerchant）。
 * 普通 NPC shop 与 cashShop 不属本组件（shop 字段留在 Character）；
 * 依赖经 owner 门面调用（getClient/getWorldServer/getMeso/gainMeso/...）。
 */
class CharacterMarket {
    private static final Logger log = LoggerFactory.getLogger(CharacterMarket.class);

    private final Character owner;

    /** 是否拥有雇佣商店（持久化到 characters.hasmerchant） */
    private boolean hasMerchant;
    /** 雇佣商店 meso 账目（持久化到 characters.merchantmesos） */
    private int merchantmeso;
    private HiredMerchant hiredMerchant = null;
    private PlayerShop playerShop = null;

    CharacterMarket(Character owner) {
        this.owner = owner;
    }

    // ── 查询 ──

    boolean hasMerchant() {
        return hasMerchant;
    }

    int getMerchantMeso() {
        return merchantmeso;
    }

    int getMerchantNetMeso() {
        int elapsedDays = 0;

        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT `timestamp` FROM `fredstorage` WHERE `cid` = ?")) {
            ps.setInt(1, owner.getId());

            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    elapsedDays = FredrickProcessor.timestampElapsedDays(rs.getTimestamp(1), System.currentTimeMillis());
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }

        if (elapsedDays > 100) {
            elapsedDays = 100;
        }

        long netMeso = merchantmeso; // negative mesos issues found thanks to Flash, Vcoc
        netMeso = (netMeso * (100 - elapsedDays)) / 100;
        return (int) netMeso;
    }

    HiredMerchant getHiredMerchant() {
        return hiredMerchant;
    }

    void setHiredMerchant(HiredMerchant hiredMerchant) {
        this.hiredMerchant = hiredMerchant;
    }

    PlayerShop getPlayerShop() {
        return playerShop;
    }

    void setPlayerShop(PlayerShop playerShop) {
        this.playerShop = playerShop;
    }

    // ── 关闭 ──

    void closePlayerShop() {
        PlayerShop mps = this.getPlayerShop();
        if (mps == null) {
            return;
        }

        if (mps.isOwner(owner)) {
            mps.setOpen(false);
            owner.getWorldServer().unregisterPlayerShop(mps);

            for (PlayerShopItem mpsi : mps.getItems()) {
                if (mpsi.getBundles() >= 2) {
                    ItemSlot iItem = mpsi.getItem().copy();
                    iItem.setQuantity((short) (mpsi.getBundles() * iItem.getQuantity()));
                    InventoryManipulator.addFromDrop(owner.getClient(), iItem, false);
                } else if (mpsi.isExist()) {
                    InventoryManipulator.addFromDrop(owner.getClient(), mpsi.getItem(), true);
                }
            }
            mps.closeShop();
        } else {
            mps.removeVisitor(owner);
        }
        this.setPlayerShop(null);
    }

    void closeHiredMerchant(boolean closeMerchant) {
        HiredMerchant merchant = this.getHiredMerchant();
        if (merchant == null) {
            return;
        }

        if (merchant.isOwner(owner) && !merchant.isPublished()) {
            merchant.closeOwnerMerchant(owner);
            return;
        }

        if (closeMerchant) {
            if (merchant.isOwner(owner) && merchant.getItems().isEmpty()) {
                merchant.forceClose();
            } else {
                merchant.removeVisitor(owner);
                this.setHiredMerchant(null);
            }
        } else {
            if (merchant.isOwner(owner)) {
                merchant.setOpen(true);
            } else {
                merchant.removeVisitor(owner);
            }
            try {
                merchant.saveItems(false);
            } catch (SQLException e) {
                log.error(I18nUtil.getLogMessage("Character.closeHiredMerchant.error1") + "{}", owner.getName(), e);
            }
        }
    }

    // ── 商店 meso 账目 ──

    void setHasMerchant(boolean set) {
        Character.characterService.update(CharactersDO.builder()
                .id(owner.getId())
                .hasmerchant(set)
                .build());
        hasMerchant = set;
    }

    void addMerchantMesos(int add) {
        final int newAmount = (int) Math.min((long) merchantmeso + add, Integer.MAX_VALUE);
        setMerchantMeso(newAmount);
    }

    void setMerchantMeso(int set) {
        Character.characterService.update(CharactersDO.builder()
                .id(owner.getId())
                .merchantmesos(set)
                .build());
        merchantmeso = set;
    }

    synchronized void withdrawMerchantMesos() {
        int merchantMeso = this.getMerchantNetMeso();
        int playerMeso = owner.getMeso();

        if (merchantMeso > 0) {
            int possible = Integer.MAX_VALUE - playerMeso;

            if (possible > 0) {
                if (possible < merchantMeso) {
                    owner.gainMeso(possible, false);
                    this.setMerchantMeso(merchantMeso - possible);
                } else {
                    owner.gainMeso(merchantMeso, false);
                    this.setMerchantMeso(0);
                }
            }
        } else {
            int nextMeso = playerMeso + merchantMeso;

            if (nextMeso < 0) {
                owner.gainMeso(-playerMeso, false);
                this.setMerchantMeso(merchantMeso + playerMeso);
            } else {
                owner.gainMeso(merchantMeso, false);
                this.setMerchantMeso(0);
            }
        }
    }
}
