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
REM 本机开发默认补上 dev profile（广告与 TapTap 的自证通道靠它打开；裸启动是关的）。
REM 已经带 prod 或 dev 的不动它——上线组合 mysql,prod 因此永远不会被这里带上 dev。
echo %SPRING_PROFILES_ACTIVE% | findstr /I "prod" >nul || echo %SPRING_PROFILES_ACTIVE% | findstr /I "dev" >nul || set "SPRING_PROFILES_ACTIVE=%SPRING_PROFILES_ACTIVE%,dev"
call mvn -q spring-boot:run
