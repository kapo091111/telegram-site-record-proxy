# 工程相機 Android App

第一版目標：

- Android APK。
- App 內拍照，不存入手機相簿。
- 可從相簿挑選原相／影片上傳。
- 用 WorkManager 做背景上傳。
- 上傳到現有 Render `/api/mobile/upload`。
- Render 再用 Google Drive 暫存及 Synology 同步。

目前狀態：

- 已建立 Android 專案骨架。
- 已定 API 設定和背景上傳 Worker。
- 需要安裝 Android Studio / Android SDK 後才可以 build APK。

