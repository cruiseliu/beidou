package org.gms;

import lombok.extern.slf4j.Slf4j;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.nio.file.Files;
import java.nio.file.Path;

@SpringBootApplication
@MapperScan("org.gms.dao.mapper")
@Slf4j
public class ServerApplication {
    public static void main(String[] args) {
        try {
            initDbDir();
        } catch (Exception e) {
            log.error("自动创建数据库目录失败：", e);
            return;
        }
        SpringApplication.run(ServerApplication.class, args);
    }

    /**
     * SQLite 库文件随首次连接自动创建，这里只保证其所在目录存在
     * （历史上 MySQL 版本此处需要连 mysql 库执行 CREATE DATABASE）
     */
    private static void initDbDir() throws Exception {
        Path dbDir = Path.of("database");
        if (Files.notExists(dbDir)) {
            Files.createDirectories(dbDir);
        }
    }
}
