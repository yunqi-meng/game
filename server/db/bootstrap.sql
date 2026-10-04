-- 一次性初始化：创建数据库、应用账号与备份账号（用 MySQL root 执行，root 口令走 -p 交互输入）
--   envsubst < server/db/bootstrap.sql | mysql -u root -p
--   （Git Bash 自带 envsubst；Windows 下也可手工替换占位符）
-- 口令只从环境变量注入，本文件不落任何字面量。需要的变量见 server/.env.example。
--   CHEMERA_DB_PASSWORD       应用账号 chem 的口令
--   CHEMERA_BACKUP_PASSWORD   备份账号 chem_bak 的口令（tools/backup.sh 用）
-- 分成两个账号是因为 mysqldump 取一致性快照需要 FLUSH_TABLES——这个权限不该给跑业务进程的账号。

CREATE DATABASE IF NOT EXISTS chemera
  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE USER IF NOT EXISTS 'chem'@'localhost' IDENTIFIED BY '${CHEMERA_DB_PASSWORD}';
CREATE USER IF NOT EXISTS 'chem'@'%'         IDENTIFIED BY '${CHEMERA_DB_PASSWORD}';
GRANT ALL PRIVILEGES ON chemera.* TO 'chem'@'localhost';
GRANT ALL PRIVILEGES ON chemera.* TO 'chem'@'%';

CREATE USER IF NOT EXISTS 'chem_bak'@'localhost' IDENTIFIED BY '${CHEMERA_BACKUP_PASSWORD}';
CREATE USER IF NOT EXISTS 'chem_bak'@'%'         IDENTIFIED BY '${CHEMERA_BACKUP_PASSWORD}';
GRANT SELECT, SHOW VIEW, TRIGGER ON chemera.* TO 'chem_bak'@'localhost';
GRANT SELECT, SHOW VIEW, TRIGGER ON chemera.* TO 'chem_bak'@'%';
GRANT FLUSH_TABLES ON *.* TO 'chem_bak'@'localhost';
GRANT FLUSH_TABLES ON *.* TO 'chem_bak'@'%';
FLUSH PRIVILEGES;

SELECT 'bootstrap done: database=chemera users=chem,chem_bak' AS result;
