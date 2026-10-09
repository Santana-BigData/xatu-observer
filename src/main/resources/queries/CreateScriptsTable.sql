CREATE TABLE IF NOT EXISTS `tb_scripts` (
    `id` bigint(20) NOT NULL AUTO_INCREMENT,
    `name` varchar(200) NOT NULL,
    `script` text NOT NULL,
    `interval_seconds` int NOT NULL,
    `timeout_seconds` int NOT NULL,
    `create_date` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_date` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4
