/*
 Navicat Premium Data Transfer

 Source Server         : windows-server
 Source Server Type    : MySQL
 Source Server Version : 80030
 Source Host           : 192.168.3.5:3307
 Source Schema         : beidou

 Target Server Type    : MySQL
 Target Server Version : 80030
 File Encoding         : 65001

 Date: 21/09/2024 17:32:51
*/


-- ----------------------------
-- Table structure for characterexplogs
-- ----------------------------
DROP TABLE IF EXISTS `characterexplogs`;
CREATE TABLE `characterexplogs`  (
    `id` INTEGER PRIMARY KEY AUTOINCREMENT,
  `world_exp_rate` int(0) NULL DEFAULT NULL,
  `exp_coupon` int(0) NULL DEFAULT NULL,
  `gained_exp` bigint(0) NULL DEFAULT NULL,
  `current_exp` bigint(0) NULL DEFAULT NULL,
  `exp_gain_time` timestamp(0) NULL DEFAULT NULL,
  `charid` int(0) NULL DEFAULT NULL

);

