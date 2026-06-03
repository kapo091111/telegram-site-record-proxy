# Telegram 工程現場記錄 Bot

Render 直接版：Telegram webhook 由 Render 接收，Render 直接用 Kenneth Google 帳戶寫入 Google Drive、建立 Google Docs、並用 PostgreSQL 記錄項目、日期、相片序號及重複相片。

不使用 Apps Script，不使用 OpenAI。

## 功能

- `/site 項目名`：新增或切換項目。
- `/site`：顯示最近項目按鈕。
- `/sites`：顯示全部項目按鈕。
- `/date`：設定今日為記錄日期。
- `/date 20260603`：設定自訂記錄日期。
- `/status`：顯示目前項目、記錄日期、相片數量。
- `/report_now`：即時生成 Google Docs。
- `/debug`：顯示除錯資料。
- 只允許指定 Telegram user ID 使用。

## Google Drive 結構

```text
工程現場記錄/
  屯門兆翠苑兆晴閣2401室/
    20260603/
      20260603001.jpg
      20260603002.jpg
      2026-06-03 - 屯門兆翠苑兆晴閣2401室.gdoc
```

## Render 環境變數

```text
PORT=3000
PUBLIC_URL=https://your-render-service.onrender.com
TIMEZONE=Asia/Hong_Kong

TELEGRAM_BOT_TOKEN=
TELEGRAM_WEBHOOK_SECRET=
ALLOWED_TELEGRAM_USER_IDS=956383250

DATABASE_URL=

GOOGLE_CLIENT_ID=
GOOGLE_CLIENT_SECRET=
GOOGLE_REFRESH_TOKEN=
GOOGLE_DRIVE_ROOT_FOLDER_ID=
```

## Google OAuth 權限

1. 在 Google Cloud 建立 OAuth Client。
2. 啟用 Google Drive API 和 Google Docs API。
3. 用 Kenneth Google 帳戶授權一次，取得 refresh token。
4. 在 Google Drive 建立或選中 `工程現場記錄` 資料夾。
5. 複製該資料夾 ID 到 `GOOGLE_DRIVE_ROOT_FOLDER_ID`。

## 本機測試

```bash
npm install
npm run build
npm test
npm start
```
