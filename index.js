import express from 'express';
import { config } from './config.js';
import { Database } from './database.js';
import { GoogleWorkspace } from './google.js';
import { ReportService } from './report-service.js';
import { scheduleDailyReports } from './scheduler.js';
import { SftpSyncService } from './sftp-sync.js';
import { SiteService } from './site-service.js';
import { createTelegramBot, configureWebhook } from './telegram.js';
async function main() {
    const db = new Database(config.databaseUrl);
    await db.migrate();
    const google = new GoogleWorkspace({
        clientId: config.googleClientId,
        clientSecret: config.googleClientSecret,
        refreshToken: config.googleRefreshToken,
        rootFolderId: config.googleDriveRootFolderId
    });
    const sites = new SiteService(db, google, config.googleSitesSheet);
    const reports = new ReportService(db, google, sites);
    const sync = config.sftp ? new SftpSyncService(db, google, config.sftp) : undefined;
    const bot = createTelegramBot({
        token: config.telegramBotToken,
        allowedUserIds: config.allowedTelegramUserIds,
        db,
        google,
        sites,
        reports,
        sync
    });
    const app = express();
    app.get('/healthz', (_req, res) => res.send('ok'));
    await configureWebhook({
        app,
        bot,
        publicUrl: config.publicUrl,
        secret: config.telegramWebhookSecret
    });
    scheduleDailyReports({
        bot,
        allowedUserIds: config.allowedTelegramUserIds,
        reports,
        sync,
        timezone: config.timezone
    });
    app.listen(config.port, () => {
        console.log(`Listening on port ${config.port}`);
    });
}
main().catch((error) => {
    console.error(error);
    process.exit(1);
});
