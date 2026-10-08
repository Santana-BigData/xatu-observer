CREATE TABLE IF NOT EXISTS `tb_api_tokens` (
    `id` bigint(20) NOT NULL AUTO_INCREMENT,
    `name` varchar(200) NOT NULL,
    `token_prefix` varchar(20) NOT NULL,
    `token_hash` char(64) NOT NULL UNIQUE,
    `read_only` boolean NOT NULL DEFAULT TRUE,
    `created_by` bigint(20) NOT NULL,
    `create_date` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `last_used` timestamp NULL DEFAULT NULL,
    `revoked_date` timestamp NULL DEFAULT NULL,
PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8
