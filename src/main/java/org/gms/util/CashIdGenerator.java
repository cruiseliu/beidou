package org.gms.util;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * cash id 发号器（petid/ringid 共用号段）：unique_id 表单行计数器，
 * `UPDATE ... RETURNING` 原子递增取号——SQLite 单写者下天然无并发重复，
 * 号只增不复用（行删除后 id 即作废），无进程内状态、无启动期预热。
 */
public class CashIdGenerator {

    private static final String KEY = "cash_id";

    /** 原子取下一个 id；表无行/DB 异常直接抛（发号失败应使调用方失败，而非静默 -1） */
    public static int generateCashId() {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("UPDATE unique_id SET value = value + 1 WHERE name = ? RETURNING value")) {
            ps.setString(1, KEY);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException("unique_id 缺少 " + KEY + " 行");
                }
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("cash id 发号失败", e);
        }
    }
}
