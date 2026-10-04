@echo off
REM 启动后端（Windows）。口令只来自环境变量或同目录 .env，本脚本不含字面量。
cd /d "%~dp0"
if exist .env for /f "usebackq tokens=1,* delims==" %%A in (`findstr /v "#" .env`) do if not defined %%A set "%%A=%%B"
if not defined CHEMERA_DB_HOST set CHEMERA_DB_HOST=localhost
if not defined CHEMERA_DB_PORT set CHEMERA_DB_PORT=3306
if not defined CHEMERA_DB_NAME set CHEMERA_DB_NAME=chemera
if not defined CHEMERA_DB_USER set CHEMERA_DB_USER=chem
if not defined CHEMERA_DB_PASSWORD (
  echo missing CHEMERA_DB_PASSWORD: copy .env.example to .env and fill it in (gitignored)
  exit /b 1
)
if not defined SPRING_PROFILES_ACTIVE set SPRING_PROFILES_ACTIVE=mysql
call mvn -q spring-boot:run
