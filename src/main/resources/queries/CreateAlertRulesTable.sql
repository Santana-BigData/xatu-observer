CREATE TABLE IF NOT EXISTS `tb_alert_rules` (
    `id` bigint(20) NOT NULL AUTO_INCREMENT,
    `name` varchar(200) NOT NULL,
    `metric` varchar(50) NOT NULL,
    `server` varchar(200) NOT NULL DEFAULT '',
    `operator` varchar(10) NOT NULL,
    `threshold` double NOT NULL,
    `duration_minutes` int NOT NULL,
    `enabled` tinyint(1) NOT NULL DEFAULT 1,
    `create_date` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_date` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4
