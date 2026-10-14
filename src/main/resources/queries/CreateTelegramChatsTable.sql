CREATE TABLE IF NOT EXISTS `tb_telegram_chats` (
    `id` bigint(20) NOT NULL AUTO_INCREMENT,
    `name` varchar(200) NOT NULL,
    `chat_id` varchar(50) NOT NULL,
    `enabled` tinyint(1) NOT NULL DEFAULT 1,
    `create_date` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_date` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
PRIMARY KEY (`id`),
UNIQUE KEY `uk_telegram_chat_id` (`chat_id`)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4
