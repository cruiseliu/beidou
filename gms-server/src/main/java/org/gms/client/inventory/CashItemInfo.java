package org.gms.client.inventory;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 点券物品（商城物/点装）的会话信息；非现金物品不持有本对象（Item.cashInfo 为 null）。
 *
 * <ul>
 * <li>cashId：运行时会话唯一编号（v83 协议要求点装在封包携带唯一 id；宠物/戒指优先用
 *     petid/ringid，其余点装用此编号）。不持久化——重启后全体重新编号，协议只要求会话内唯一。</li>
 * <li>sn：购买来源的商城商品序列号（ModifiedCashItemDO 落地时携带），供客户端回显/赠送。
 *     不持久化——重启后老物品 sn 归零，赠送对老物品实际不可用（上游遗留缺陷）。</li>
 * <li>giftFrom：赠送者署名（商城礼物；MTS 亦复用该列做转卖来源——非现金物品不携带）。</li>
 * </ul>
 */
public class CashItemInfo {
    /** pets & rings share cashid values */
    private static final AtomicInteger RUNNING_CASH_ID = new AtomicInteger(777000000);

    /** 0 = 尚未领取（首次 getCashId 时惰性领号） */
    private int cashId;
    private int sn;

    /** 赠送者署名（商城礼物/MTS 转卖来源） */
    private String giftFrom = "";

    public int getCashId() {
        if (cashId == 0) {
            cashId = RUNNING_CASH_ID.getAndIncrement();
        }
        return cashId;
    }

    public int getSN() {
        return sn;
    }

    public void setSN(int sn) {
        this.sn = sn;
    }

    public String getGiftFrom() {
        return giftFrom;
    }

    public void setGiftFrom(String giftFrom) {
        this.giftFrom = giftFrom;
    }
}
