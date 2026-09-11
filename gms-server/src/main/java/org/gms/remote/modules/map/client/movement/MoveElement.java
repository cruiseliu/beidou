package org.gms.remote.modules.map.client.movement;

/**
 * 移动语义元素：玩家移动包解码后的最小单元（map 模块词汇表）。
 *
 * <p>完整性约束（doc/13）：每个 wire command 显式建模、每个字段有归位——decode/encode
 * 严格对称，byte buffer 不跨出 gms083 层。command 为 wire 判别值（int 承载），
 * 保留字段按 wire 位显式建模（重编码逐字节必需）。
 */
public sealed interface MoveElement
        permits AbsoluteMove, RelativeMove, TeleportMove, ChairMove, JumpDownMove,
        ChangeEquipMove, LegacyMove9, LegacyMove3 {
}
