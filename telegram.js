import { Markup, Telegraf } from 'telegraf';
import { hkDate, mediaFileName, normaliseDate } from './date.js';
import { requireAllowedUser } from './auth.js';
const DOCUMENT_TYPES = {
    'image/jpeg': { extension: 'jpg', label: '圖片' },
    'image/png': { extension: 'png', label: '圖片' },
    'image/webp': { extension: 'webp', label: '圖片' },
    'application/pdf': { extension: 'pdf', label: 'PDF' }
};
export function createTelegramBot(input) {
    const bot = new Telegraf(input.token);
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
            await sendSiteButtons(ctx, input.db);
            return;
        }
        const site = await input.sites.useSite(ctx.from.id, name);
        await ctx.reply(`已切換到：${site.name}`);
    });
    bot.command('sites', async (ctx) => {
        await sendSiteButtons(ctx, input.db, 50);
    });
    bot.command('archive_site', async (ctx) => {
        await sendArchiveSiteButtons(ctx, input.db);
    });
    bot.command('completed_sites', async (ctx) => {
        await sendCompletedSiteButtons(ctx, input.db);
    });
    bot.command('delete_site', async (ctx) => {
        await sendDeleteSiteButtons(ctx, input.db);
    });
    bot.action(/^site:(.+)$/, async (ctx) => {
        const siteId = ctx.match[1];
        const sites = await input.db.listSites(ctx.from.id, 50);
        const site = sites.find((candidate) => candidate.id === siteId);
        if (!site) {
            await ctx.answerCbQuery('找不到項目');
            return;
        }
        await input.db.setCurrentSite(ctx.from.id, site.id);
        await ctx.answerCbQuery(site.name);
        await ctx.editMessageText(`已切換到：${site.name}`);
    });
    bot.action(/^archive_site:(.+)$/, async (ctx) => {
        const siteId = ctx.match[1];
        const sites = await input.db.listSites(ctx.from.id, 50);
        const site = sites.find((candidate) => candidate.id === siteId);
        if (!site) {
            await ctx.answerCbQuery('找不到項目');
            return;
        }
        await input.db.archiveSite(ctx.from.id, site.id);
        await ctx.answerCbQuery(site.name);
        await ctx.editMessageText(`已完成並隱藏地盤：${site.name}`);
    });
    bot.action(/^restore_site:(.+)$/, async (ctx) => {
        const siteId = ctx.match[1];
        const site = await input.db.restoreSite(ctx.from.id, siteId);
        if (!site) {
            await ctx.answerCbQuery('找不到項目');
            return;
        }
        await ctx.answerCbQuery(site.name);
        await ctx.editMessageText(`已恢復並切換到：${site.name}`);
    });
    bot.action(/^delete_site:(.+)$/, async (ctx) => {
        const siteId = ctx.match[1];
        const sites = await input.db.listSites(ctx.from.id, 50);
        const site = sites.find((candidate) => candidate.id === siteId);
        if (!site) {
            await ctx.answerCbQuery('找不到項目');
            return;
        }
        const result = await input.db.deleteSiteIfEmpty(ctx.from.id, site.id);
        if (!result.deleted) {
            await ctx.answerCbQuery('不可刪除');
            await ctx.editMessageText(`不可刪除：${site.name}\n已有檔案 ${result.fileCount} 個、文件 ${result.reportCount} 份。\n如項目已完工，請用「完成地盤」隱藏。`);
            return;
        }
        await ctx.answerCbQuery(site.name);
        await ctx.editMessageText(`已刪除地盤：${site.name}`);
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
        await input.db.setRecordDate(ctx.from.id, date);
        await ctx.reply(`記錄日期已設定：${date}`);
    });
    bot.action('menu:sites', async (ctx) => {
        await ctx.answerCbQuery();
        await sendSiteButtons(ctx, input.db);
    });
    bot.action('menu:archive_site', async (ctx) => {
        await ctx.answerCbQuery();
        await sendArchiveSiteButtons(ctx, input.db);
    });
    bot.action('menu:completed_sites', async (ctx) => {
        await ctx.answerCbQuery();
        await sendCompletedSiteButtons(ctx, input.db);
    });
    bot.action('menu:delete_site', async (ctx) => {
        await ctx.answerCbQuery();
        await sendDeleteSiteButtons(ctx, input.db);
    });
    bot.action('menu:date', async (ctx) => {
        await ctx.answerCbQuery();
        await sendDateButtons(ctx);
    });
    bot.action('menu:status', async (ctx) => {
        await ctx.answerCbQuery();
        await sendStatus(ctx, input.db, input.sites);
    });
    bot.action('menu:report', async (ctx) => {
        await ctx.answerCbQuery();
        const site = await input.sites.currentSite(ctx.from.id);
        if (!site) {
            await ctx.reply('未選擇項目。請先選擇地盤。');
            await sendSiteButtons(ctx, input.db);
            return;
        }
        const report = await input.reports.createReportForSite(site, await currentRecordDate(input.db, ctx.from.id));
        await ctx.reply(report.message);
    });
    bot.action(/^date:(today|yesterday|before_yesterday)$/, async (ctx) => {
        const selected = ctx.match[1];
        const offset = selected === 'today' ? 0 : selected === 'yesterday' ? -1 : -2;
        const date = hkDate(addHongKongDays(offset));
        await input.db.setRecordDate(ctx.from.id, date);
        await ctx.answerCbQuery(date);
        await ctx.editMessageText(`記錄日期已設定：${date}`);
    });
    bot.action('date:custom', async (ctx) => {
        await ctx.answerCbQuery();
        await ctx.editMessageText('請直接輸入自訂日期，例如：/date 20260603');
    });
    bot.command('status', async (ctx) => {
        await sendStatus(ctx, input.db, input.sites);
    });
    bot.command('debug', async (ctx) => {
        const site = await input.sites.currentSite(ctx.from.id);
        await ctx.reply([
            `userId=${ctx.from.id}`,
            `allowed=${input.allowedUserIds.join(',')}`,
            `currentSite=${site?.name || ''}`,
            `recordDate=${await currentRecordDate(input.db, ctx.from.id)}`,
            `sites=${JSON.stringify((await input.db.listSites(ctx.from.id, 50)).map((site) => site.name))}`,
            'backend=render',
            'version=render-completed-sites-20260604'
        ].join('\n'));
    });
    bot.command('report_now', async (ctx) => {
        const site = await input.sites.currentSite(ctx.from.id);
        if (!site) {
            await ctx.reply('未選擇項目。請先輸入 /site 項目名。');
            return;
        }
        const report = await input.reports.createReportForSite(site, await currentRecordDate(input.db, ctx.from.id));
        await ctx.reply(report.message);
    });
    bot.on('photo', async (ctx) => {
        const photo = ctx.message.photo.at(-1);
        if (!photo) {
            await ctx.reply('收不到相片。');
            return;
        }
        await uploadTelegramFile(ctx, input, {
            fileId: photo.file_id,
            fileUniqueId: photo.file_unique_id,
            mimeType: 'image/jpeg',
            extension: 'jpg',
            label: '相片'
        });
    });
    bot.on('document', async (ctx) => {
        const document = ctx.message.document;
        const type = document.mime_type ? DOCUMENT_TYPES[document.mime_type] : null;
        if (!type) {
            await ctx.reply('暫時只支援圖片檔案、PDF、影片。');
            return;
        }
        await uploadTelegramFile(ctx, input, {
            fileId: document.file_id,
            fileUniqueId: document.file_unique_id,
            mimeType: document.mime_type || 'application/octet-stream',
            extension: extensionFromName(document.file_name) || type.extension,
            label: type.label
        });
    });
    bot.on('video', async (ctx) => {
        const video = ctx.message.video;
        await uploadTelegramFile(ctx, input, {
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
    const site = await input.sites.currentSite(ctx.from.id);
    if (!site) {
        await ctx.reply('未選擇項目。請先輸入 /site 項目名。');
        return;
    }
    const existing = await input.db.photoByTelegramUniqueId(file.fileUniqueId);
    if (existing) {
        await ctx.reply(`這個檔案已上傳過：${existing.fileName}`);
        return;
    }
    const date = await currentRecordDate(input.db, ctx.from.id);
    await ctx.reply(`收到${file.label}，正在上傳到 Google Drive。`);
    const dateFolderId = await input.sites.ensureDateFolder(site, date);
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
        userId: ctx.from.id,
        date,
        sequence,
        fileName,
        telegramFileUniqueId: file.fileUniqueId,
        driveFileId: uploaded.id,
        driveUrl: uploaded.url
    });
    await ctx.reply(`已上傳：${fileName}`);
}
async function currentRecordDate(db, userId) {
    return (await db.currentRecordDate(userId)) || hkDate();
}
async function sendSiteButtons(ctx, db, limit = 10) {
    const sites = await db.listSites(ctx.from.id, limit);
    if (sites.length === 0) {
        await ctx.reply('未有項目。請輸入 /site 項目名。');
        return;
    }
    await ctx.reply('請選擇項目：', Markup.inlineKeyboard(sites.map((site) => Markup.button.callback(site.name, `site:${site.id}`)), { columns: 1 }));
}
async function sendArchiveSiteButtons(ctx, db) {
    const sites = await db.listSites(ctx.from.id, 50);
    if (sites.length === 0) {
        await ctx.reply('未有可完成的地盤。');
        return;
    }
    await ctx.reply('選擇要完成並隱藏的地盤：', Markup.inlineKeyboard(sites.map((site) => Markup.button.callback(site.name, `archive_site:${site.id}`)), { columns: 1 }));
}
async function sendCompletedSiteButtons(ctx, db) {
    const sites = await db.listArchivedSites(ctx.from.id, 50);
    if (sites.length === 0) {
        await ctx.reply('未有已完成地盤。');
        return;
    }
    await ctx.reply('已完成地盤：\n點選後會恢復並切換到該地盤，可以繼續上傳相片或文件。', Markup.inlineKeyboard(sites.map((site) => Markup.button.callback(site.name, `restore_site:${site.id}`)), { columns: 1 }));
}
async function sendDeleteSiteButtons(ctx, db) {
    const sites = await db.listSites(ctx.from.id, 50);
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
        { command: 'archive_site', description: '完成並隱藏地盤' },
        { command: 'completed_sites', description: '查看已完成地盤' },
        { command: 'delete_site', description: '刪除打錯的地盤' },
        { command: 'date', description: '設定記錄日期' },
        { command: 'debug', description: '顯示除錯資料' },
        { command: 'whoami', description: '顯示 Telegram user ID' }
    ]);
}
async function sendMainMenu(ctx) {
    await ctx.reply('請選擇操作：', Markup.inlineKeyboard([
        [Markup.button.callback('選地盤', 'menu:sites'), Markup.button.callback('選日期', 'menu:date')],
        [Markup.button.callback('完成地盤', 'menu:archive_site'), Markup.button.callback('已完成地盤', 'menu:completed_sites')],
        [Markup.button.callback('刪除地盤', 'menu:delete_site')]
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
async function sendStatus(ctx, db, sites) {
    const site = await sites.currentSite(ctx.from.id);
    const date = await currentRecordDate(db, ctx.from.id);
    const files = site ? await db.photosForDate(site.id, date) : [];
    await ctx.reply([
        `目前項目：${site?.name || '未選擇'}`,
        `記錄日期：${date}`,
        `檔案：${files.length} 個`
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
