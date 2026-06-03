import 'dotenv/config';
function required(name) {
    const value = process.env[name];
    if (!value) {
        throw new Error(`Missing required environment variable: ${name}`);
    }
    return value;
}
function optional(name, fallback = '') {
    return process.env[name] || fallback;
}
export const config = {
    port: Number(optional('PORT', '3000')),
    publicUrl: required('PUBLIC_URL').replace(/\/$/, ''),
    timezone: optional('TIMEZONE', 'Asia/Hong_Kong'),
    telegramBotToken: required('TELEGRAM_BOT_TOKEN'),
    telegramWebhookSecret: required('TELEGRAM_WEBHOOK_SECRET'),
    allowedTelegramUserIds: required('ALLOWED_TELEGRAM_USER_IDS')
        .split(',')
        .map((id) => Number(id.trim()))
        .filter((id) => Number.isFinite(id)),
    databaseUrl: required('DATABASE_URL'),
    googleClientId: required('GOOGLE_CLIENT_ID'),
    googleClientSecret: required('GOOGLE_CLIENT_SECRET'),
    googleRefreshToken: required('GOOGLE_REFRESH_TOKEN'),
    googleDriveRootFolderId: required('GOOGLE_DRIVE_ROOT_FOLDER_ID')
};
if (config.allowedTelegramUserIds.length === 0) {
    throw new Error('ALLOWED_TELEGRAM_USER_IDS must contain at least one Telegram user ID');
}
