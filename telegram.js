import { Markup, Telegraf } from 'telegraf';
import { dateFolderName, hkDate, mediaFileName, normaliseDate } from './date.js';
import { requireAllowedUser } from './auth.js';
export function createTelegramBot(input) {
    const bot = new Telegraf(input.token);
    const ownerUserId = input.allowedUserIds[0];
    bot.command('whoami', async (ctx) => {
        await ctx.reply(`Telegram user ID：${ctx.from.id}`);
    });
    bot.use(requireAllowedUser(input.allowedUserIds));
    bot.start(async (ctx) => {
        await setTelegramCommands(bot);
        await sendMainMenu(ctx);
    });
    bot.command('menu', async (ctx) => {
        await sendMainMenu(ctx);
    });
    bot.command('site', async (ctx) => {
        const name = commandText(ctx.message.text, '/site');
        if (!name) {
            await syncSitesQuietly(input.sites, ownerUserId);
            await sendSiteButtons(ctx, input.db, ownerUserId);
            return;
        }
        const site = await input.sites.useSite(ownerUserId, name);
        await ctx.reply(`已切換到：${site.name}`);
    });
    bot.command('sites', async (ctx) => {
        await syncSitesQuietly(input.sites, ownerUserId);
        await sendSiteButtons(ctx, input.db, ownerUserId, 50);
    });
    bot.command('sync_sites', async (ctx) => {
        try {
            const result = await input.sites.syncSitesFromSheet(ownerUserId);
            await ctx.reply(`已同步 Google Sheet 地盤清單：${result.active} 個使用中，${result.archived} 個已完成。`);
        }
        catch (error) {
            console.error(error);
            await ctx.reply(describeGoogleSheetError(error));
        }
    });
    bot.command('archive_site', async (ctx) => {
        await sendArchiveSiteButtons(ctx, input.db, ownerUserId);
    });
    bot.command('completed_sites', async (ctx) => {
        await sendCompletedSiteButtons(ctx, input.db, ownerUserId);
    });
    bot.command('delete_site', async (ctx) => {
        await sendDeleteSiteButtons(ctx, input.db, ownerUserId);
    });
    bot.action(/^site:(.+)$/, async (ctx) => {
        const siteId = ctx.match[1];
        const sites = await input.db.listSites(ownerUserId, 50);
        const site = sites.find((candidate) => candidate.id === siteId);
        if (!site) {
            await ctx.answerCbQuery('找不到項目');
            return;
        }
        await input.db.setCurrentSite(ownerUserId, site.id);
        await ctx.answerCbQuery(site.name);
        await ctx.editMessageText(`已切換到：${site.name}`);
    });
    bot.action(/^archive_site:(.+)$/, async (ctx) => {
        const siteId = ctx.match[1];
        const sites = await input.db.listSites(ownerUserId, 50);
        const site = sites.find((candidate) => candidate.id === siteId);
        if (!site) {
            await ctx.answerCbQuery('找不到項目');
            return;
        }
        await input.db.archiveSite(ownerUserId, site.id);
        await ctx.answerCbQuery(site.name);
        await ctx.editMessageText(`已完成並隱藏地盤：${site.name}`);
    });
    bot.action(/^restore_site:(.+)$/, async (ctx) => {
        const siteId = ctx.match[1];
        const site = await input.db.restoreSite(ownerUserId, siteId);
        if (!site) {
            await ctx.answerCbQuery('找不到項目');
            return;
        }
        await ctx.answerCbQuery(site.name);
        await ctx.editMessageText(`已恢復並切換到：${site.name}`);
    });
    bot.action(/^delete_site:(.+)$/, async (ctx) => {
        const siteId = ctx.match[1];
        const sites = await input.db.listSites(ownerUserId, 50);
        const site = sites.find((candidate) => candidate.id === siteId);
        if (!site) {
            await ctx.answerCbQuery('找不到項目');
            return;
        }
        const result = await input.db.deleteSiteIfEmpty(ownerUserId, site.id);
        if (!result.deleted) {
            await ctx.answerCbQuery('不可刪除');
            await ctx.editMessageText(`不可刪除：${site.name}\n已有檔案 ${result.fileCount} 個、文件 ${result.reportCount} 份。\n如項目已完工，請用「完成地盤」隱藏。`);
            return;
        }
        await ctx.answerCbQuery(site.name);
        await ctx.editMessageText(`已刪除地盤：${site.name}`);
    });
    bot.command('remark', async (ctx) => {
        const remark = commandText(ctx.message.text, '/remark');
        if (!remark) {
            await sendRemarkButtons(ctx);
            return;
        }
        await input.db.setFileRemark(ownerUserId, remark === '清除' ? null : remark);
        await ctx.reply(remark === '清除' ? '已清除檔案備注。' : `檔案備注已設定：${remark}`);
    });
    bot.command('date', async (ctx) => {
        const rawDate = commandText(ctx.message.text, '/date');
        if (!rawDate) {
            await sendDateButtons(ctx);
            return;
        }
        const date = normaliseDate(rawDate);
        if (!date) {
            await ctx.reply('日期格式錯誤。請用 /date 20260603 或 /date 2026-06-03。');
            return;
        }
        await input.db.setRecordDate(ownerUserId, date);
        await ctx.reply(`記錄日期已設定：${date}`);
    });
    bot.action('menu:sites', async (ctx) => {
        await ctx.answerCbQuery();
        await syncSitesQuietly(input.sites, ownerUserId);
        await sendSiteButtons(ctx, input.db, ownerUserId);
    });
    bot.action('menu:archive_site', async (ctx) => {
        await ctx.answerCbQuery();
        await sendArchiveSiteButtons(ctx, input.db, ownerUserId);
    });
    bot.action('menu:completed_sites', async (ctx) => {
        await ctx.answerCbQuery();
        await sendCompletedSiteButtons(ctx, input.db, ownerUserId);
    });
    bot.action('menu:delete_site', async (ctx) => {
        await ctx.answerCbQuery();
        await sendDeleteSiteButtons(ctx, input.db, ownerUserId);
    });
    bot.action('menu:remark', async (ctx) => {
        await ctx.answerCbQuery();
        await sendRemarkButtons(ctx);
    });
    bot.action('menu:date', async (ctx) => {
        await ctx.answerCbQuery();
        await sendDateButtons(ctx);
    });
    bot.action('menu:status', async (ctx) => {
        await ctx.answerCbQuery();
        await sendStatus(ctx, input.db, input.sites, ownerUserId);
    });
    bot.action('menu:report', async (ctx) => {
        await ctx.answerCbQuery();
        const site = await input.sites.currentSite(ownerUserId);
        if (!site) {
            await ctx.reply('未選擇項目。請先選擇地盤。');
            await sendSiteButtons(ctx, input.db, ownerUserId);
            return;
        }
        const report = await input.reports.createReportForSite(site, await currentRecordDate(input.db, ownerUserId));
        await ctx.reply(report.message);
    });
    bot.action(/^remark:(.+)$/, async (ctx) => {
        const value = ctx.match[1];
        const remark = value === 'clear' ? null : value;
        await input.db.setFileRemark(ownerUserId, remark);
        await ctx.answerCbQuery(remark || '清除');
        await ctx.editMessageText(remark ? `檔案備注已設定：${remark}` : '已清除檔案備注。');
    });
    bot.action(/^date:(today|yesterday|before_yesterday)$/, async (ctx) => {
        const selected = ctx.match[1];
        const offset = selected === 'today' ? 0 : selected === 'yesterday' ? -1 : -2;
        const date = hkDate(addHongKongDays(offset));
        await input.db.setRecordDate(ownerUserId, date);
        await ctx.answerCbQuery(date);
        await ctx.editMessageText(`記錄日期已設定：${date}`);
    });
    bot.action('date:custom', async (ctx) => {
        await ctx.answerCbQuery();
        await ctx.editMessageText('請直接輸入自訂日期，例如：/date 20260603');
    });
    bot.command('status', async (ctx) => {
        await sendStatus(ctx, input.db, input.sites, ownerUserId);
    });
    bot.command('debug', async (ctx) => {
        const site = await input.sites.currentSite(ownerUserId);
        await ctx.reply([
            `userId=${ctx.from.id}`,
            `ownerUserId=${ownerUserId}`,
            `allowed=${input.allowedUserIds.join(',')}`,
            `currentSite=${site?.name || ''}`,
            `recordDate=${await currentRecordDate(input.db, ownerUserId)}`,
            `sites=${JSON.stringify((await input.db.listSites(ownerUserId, 50)).map((site) => site.name))}`,
            'backend=render',
            'version=render-auto-sync-sites-20260605'
        ].join('\n'));
    });
    bot.command('report_now', async (ctx) => {
        const site = await input.sites.currentSite(ownerUserId);
        if (!site) {
            await ctx.reply('未選擇項目。請先輸入 /site 項目名。');
            return;
        }
        const report = await input.reports.createReportForSite(site, await currentRecordDate(input.db, ownerUserId));
        await ctx.reply(report.message);
    });
    bot.command('sync_today', async (ctx) => {
        if (!input.sync) {
            await ctx.reply('未設定 Synology 同步。請先在 Render 加 SFTP 環境變數。');
            return;
        }
        const date = await currentRecordDate(input.db, ownerUserId);
        await ctx.reply(`開始同步 ${date} 到 Synology。`);
        const result = await input.sync.syncDate(date);
        const uploaded = result.reduce((sum, item) => sum + item.uploaded, 0);
        await ctx.reply(`已同步到 Synology：${result.length} 個地盤，${uploaded} 個檔案。`);
    });
    bot.command('sync_pending', async (ctx) => {
        if (!input.sync) {
            await ctx.reply('未設定 Synology 同步。請先在 Render 加 SFTP 環境變數。');
            return;
        }
        await ctx.reply('開始補傳所有未同步檔案到 Synology。');
        const result = await input.sync.syncPendingDates();
        const uploaded = result.reduce((sum, item) => sum + item.uploaded, 0);
        await ctx.reply(`已補傳到 Synology：${result.length} 個地盤日期，${uploaded} 個檔案。`);
    });
    bot.on('photo', async (ctx) => {
        const photo = ctx.message.photo.at(-1);
        if (!photo) {
            await ctx.reply('收不到相片。');
            return;
        }
        await uploadTelegramFile(ctx, input, {
            ownerUserId,
            fileId: photo.file_id,
            fileUniqueId: photo.file_unique_id,
            mimeType: 'image/jpeg',
            extension: 'jpg',
            label: '相片'
        });
    });
    bot.on('document', async (ctx) => {
        const document = ctx.message.document;
        await uploadTelegramFile(ctx, input, {
            ownerUserId,
            fileId: document.file_id,
            fileUniqueId: document.file_unique_id,
            mimeType: document.mime_type || 'application/octet-stream',
            extension: extensionFromName(document.file_name) || extensionFromMime(document.mime_type) || 'bin',
            label: '檔案'
        });
    });
    bot.on('video', async (ctx) => {
        const video = ctx.message.video;
        await uploadTelegramFile(ctx, input, {
            ownerUserId,
            fileId: video.file_id,
            fileUniqueId: video.file_unique_id,
            mimeType: video.mime_type || 'video/mp4',
            extension: extensionFromName(video.file_name) || extensionFromMime(video.mime_type) || 'mp4',
            label: '影片'
        });
    });
    bot.catch(async (error, ctx) => {
        console.error(error);
        await ctx.reply('出錯。請用 /debug 檢查狀態，或稍後再試。');
    });
    return bot;
}
export async function configureWebhook(input) {
    input.app.use(input.bot.webhookCallback('/telegram', {
        secretToken: input.secret
    }));
    await input.bot.telegram.setWebhook(`${input.publicUrl}/telegram`, {
        secret_token: input.secret,
        allowed_updates: ['message', 'callback_query']
    });
    await setTelegramCommands(input.bot);
}
async function uploadTelegramFile(ctx, input, file) {
    const site = await input.sites.currentSite(file.ownerUserId);
    if (!site) {
        await ctx.reply('未選擇項目。請先輸入 /site 項目名。');
        return;
    }
    const existing = await input.db.photoByTelegramUniqueId(file.fileUniqueId);
    if (existing) {
        await ctx.reply(`這個檔案已上傳過：${existing.fileName}`);
        return;
    }
    const date = await currentRecordDate(input.db, file.ownerUserId);
    await ctx.reply(`收到${file.label}，正在暫存到 Google Drive。`);
    const remark = await input.db.currentFileRemark(file.ownerUserId);
    const folderName = dateFolderName(date, remark || '');
    const dateFolderId = await input.sites.ensureDateFolder(site, date, remark || '');
    const sequence = await input.db.nextPhotoSequence(site.id, date);
    const fileName = mediaFileName(date, sequence, file.extension);
    const fileUrl = await ctx.telegram.getFileLink(file.fileId);
    const response = await fetch(fileUrl);
    if (!response.ok) {
        throw new Error(`Failed to download Telegram file: ${response.status}`);
    }
    const uploaded = await input.google.uploadFile({
        folderId: dateFolderId,
        fileName,
        mimeType: file.mimeType,
        buffer: Buffer.from(await response.arrayBuffer())
    });
    await input.db.insertPhoto({
        siteId: site.id,
        userId: file.ownerUserId,
        date,
        sequence,
        fileName,
        folderName,
        telegramFileUniqueId: file.fileUniqueId,
        driveFileId: uploaded.id,
        driveUrl: uploaded.url
    });
    await input.db.setFileRemark(file.ownerUserId, null);
    if (!input.sync) {
        await ctx.reply(`已暫存：${fileName}`);
        return;
    }
    try {
        await input.sync.syncDate(date);
        await ctx.reply(`已上傳到 Synology：${fileName}`);
    }
    catch (error) {
        console.error(error);
        await ctx.reply(`已暫存：${fileName}\nSynology 暫時未連到，稍後會自動補傳。`);
    }
}
async function syncSitesQuietly(sites, ownerUserId) {
    try {
        await sites.syncSitesFromSheet(ownerUserId);
    }
    catch (error) {
        console.error(error);
    }
}
async function currentRecordDate(db, userId) {
    return (await db.currentRecordDate(userId)) || hkDate();
}
async function sendSiteButtons(ctx, db, ownerUserId, limit = 10) {
    const sites = await db.listSites(ownerUserId, limit);
    if (sites.length === 0) {
        await ctx.reply('未有項目。請輸入 /site 項目名。');
        return;
    }
    await ctx.reply('請選擇項目：', Markup.inlineKeyboard(sites.map((site) => Markup.button.callback(site.name, `site:${site.id}`)), { columns: 1 }));
}
async function sendArchiveSiteButtons(ctx, db, ownerUserId) {
    const sites = await db.listSites(ownerUserId, 50);
    if (sites.length === 0) {
        await ctx.reply('未有可完成的地盤。');
        return;
    }
    await ctx.reply('選擇要完成並隱藏的地盤：', Markup.inlineKeyboard(sites.map((site) => Markup.button.callback(site.name, `archive_site:${site.id}`)), { columns: 1 }));
}
async function sendCompletedSiteButtons(ctx, db, ownerUserId) {
    const sites = await db.listArchivedSites(ownerUserId, 50);
    if (sites.length === 0) {
        await ctx.reply('未有已完成地盤。');
        return;
    }
    await ctx.reply('已完成地盤：\n點選後會恢復並切換到該地盤，可以繼續上傳相片或文件。', Markup.inlineKeyboard(sites.map((site) => Markup.button.callback(site.name, `restore_site:${site.id}`)), { columns: 1 }));
}
async function sendDeleteSiteButtons(ctx, db, ownerUserId) {
    const sites = await db.listSites(ownerUserId, 50);
    if (sites.length === 0) {
        await ctx.reply('未有可刪除的地盤。');
        return;
    }
    await ctx.reply('選擇要刪除的地盤：\n只可刪除未有檔案或文件的地盤。', Markup.inlineKeyboard(sites.map((site) => Markup.button.callback(site.name, `delete_site:${site.id}`)), { columns: 1 }));
}
async function setTelegramCommands(bot) {
    await bot.telegram.setMyCommands([
        { command: 'menu', description: '開啟主選單' },
        { command: 'site', description: '新增或選擇項目' },
        { command: 'sites', description: '顯示全部項目' },
        { command: 'sync_sites', description: '同步 Google Sheet 地盤清單' },
        { command: 'archive_site', description: '完成並隱藏地盤' },
        { command: 'completed_sites', description: '查看已完成地盤' },
        { command: 'delete_site', description: '刪除打錯的地盤' },
        { command: 'date', description: '設定記錄日期' },
        { command: 'remark', description: '設定檔案備注' },
        { command: 'sync_today', description: '同步今日到 Synology' },
        { command: 'sync_pending', description: '補傳未同步檔案' },
        { command: 'debug', description: '顯示除錯資料' },
        { command: 'whoami', description: '顯示 Telegram user ID' }
    ]);
}
async function sendMainMenu(ctx) {
    await ctx.reply('請選擇操作：', Markup.inlineKeyboard([
        [Markup.button.callback('選地盤', 'menu:sites'), Markup.button.callback('選日期', 'menu:date')],
        [Markup.button.callback('檔案備注', 'menu:remark')],
        [Markup.button.callback('完成地盤', 'menu:archive_site'), Markup.button.callback('已完成地盤', 'menu:completed_sites')],
        [Markup.button.callback('刪除地盤', 'menu:delete_site')]
    ]));
}
async function sendRemarkButtons(ctx) {
    await ctx.reply('請選擇檔案備注：', Markup.inlineKeyboard([
        [
            Markup.button.callback('打拆', 'remark:打拆'),
            Markup.button.callback('水電完成', 'remark:水電完成')
        ],
        [
            Markup.button.callback('泥水完成', 'remark:泥水完成'),
            Markup.button.callback('清除', 'remark:clear')
        ]
    ]));
}
async function sendDateButtons(ctx) {
    await ctx.reply('請選擇記錄日期：', Markup.inlineKeyboard([
        [
            Markup.button.callback('今日', 'date:today'),
            Markup.button.callback('昨日', 'date:yesterday'),
            Markup.button.callback('前日', 'date:before_yesterday')
        ],
        [
            Markup.button.callback('自訂日期', 'date:custom')
        ]
    ]));
}
async function sendStatus(ctx, db, sites, ownerUserId) {
    const site = await sites.currentSite(ownerUserId);
    const date = await currentRecordDate(db, ownerUserId);
    const counts = site ? await db.syncCountsForDate(site.id, date) : { total: 0, synced: 0, pending: 0 };
    const remark = await db.currentFileRemark(ownerUserId);
    await ctx.reply([
        `目前項目：${site?.name || '未選擇'}`,
        `記錄日期：${date}`,
        `檔案備注：${remark || '無'}`,
        `檔案：${counts.total} 個`,
        `已同步：${counts.synced} 個`,
        `待補傳：${counts.pending} 個`
    ].join('\n'));
}
function commandText(text, command) {
    return text.replace(new RegExp(`^${command}(?:@\\S+)?\\s*`, 'i'), '').trim();
}
function extensionFromName(fileName) {
    const match = fileName?.match(/\.([a-zA-Z0-9]{1,8})$/);
    return match ? match[1].toLowerCase() : null;
}
function extensionFromMime(mimeType) {
    if (mimeType === 'video/mp4')
        return 'mp4';
    if (mimeType === 'video/quicktime')
        return 'mov';
    if (mimeType === 'video/x-msvideo')
        return 'avi';
    if (mimeType === 'video/x-matroska')
        return 'mkv';
    return null;
}
function addHongKongDays(days) {
    const now = new Date();
    const hkNow = new Date(now.toLocaleString('en-US', { timeZone: 'Asia/Hong_Kong' }));
    hkNow.setDate(hkNow.getDate() + days);
    return hkNow;
}
function describeGoogleSheetError(error) {
    const status = typeof error === 'object' && error !== null && 'status' in error ? error.status : null;
    const message = error instanceof Error ? error.message : String(error);
    if (status === 403 || message.includes('insufficient authentication scopes')) {
        return 'Google Sheet 權限不足。多數係 OAuth refresh token 未包含 Google Sheets 權限，要重新授權。';
    }
    if (status === 404 || message.includes('Requested entity was not found')) {
        return '搵唔到 Google Sheet。請檢查 GOOGLE_SITES_SHEET_ID 是否正確，並確認 Sheet 已分享給授權帳戶。';
    }
    if (message.includes('Unable to parse range') || message.includes('range')) {
        return 'Google Sheet range 錯。請檢查 GOOGLE_SITES_SHEET_RANGE，例如 Sites!A:C。';
    }
    return `未能同步 Google Sheet：${message}`;
}
