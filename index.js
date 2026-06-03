import express from 'express';
import { config } from './config.js';
import { Database } from './database.js';
import { GoogleWorkspace } from './google.js';
import { ReportService } from './report-service.js';
import { scheduleDailyReports } from './scheduler.js';
import { SiteService } from './site-service.js';
import { createTelegramBot, configureWebhook } from './telegram.js';
async function main() {
    const db = new Database(config.databaseUrl);
    await db.migrate();
    const google = new GoogleWorkspace({
        serviceAccountEmail: config.googleServiceAccountEmail,
        privateKey: config.googlePrivateKey,
        rootFolderId: config.googleDriveRootFolderId
    });
    const sites = new SiteService(db, google);
    const reports = new ReportService(db, google, sites);
    const bot = createTelegramBot({
        token: config.telegramBotToken,
        allowedUserIds: config.allowedTelegramUserIds,
        db,
        google,
        sites,
        reports
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
