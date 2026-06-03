import { Markup, Telegraf } from 'telegraf';
import { hkDate, normaliseDate, photoFileName } from './date.js';
import { requireAllowedUser } from './auth.js';
export function createTelegramBot(input) {
    const bot = new Telegraf(input.token);
    bot.command('whoami', async (ctx) => {
        await ctx.reply(`Telegram user ID：${ctx.from.id}`);
    });
    bot.use(requireAllowedUser(input.allowedUserIds));
    bot.start(async (ctx) => {
        await setTelegramCommands(bot);
        await ctx.reply('已啟動。先用 /site 項目名 選項目，再用 /date 選日期，之後直接傳相。');
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
    bot.command('date', async (ctx) => {
        const rawDate = commandText(ctx.message.text, '/date');
        const date = rawDate ? normaliseDate(rawDate) : hkDate();
        if (!date) {
            await ctx.reply('日期格式錯誤。請用 /date 20260603 或 /date 2026-06-03。');
            return;
        }
        await input.db.setRecordDate(ctx.from.id, date);
        await ctx.reply(`記錄日期已設定：${date}`);
    });
    bot.command('status', async (ctx) => {
        const site = await input.sites.currentSite(ctx.from.id);
        const date = await currentRecordDate(input.db, ctx.from.id);
        const photos = site ? await input.db.photosForDate(site.id, date) : [];
        await ctx.reply([
            `目前項目：${site?.name || '未選擇'}`,
            `記錄日期：${date}`,
            `相片：${photos.length} 張`
        ].join('\n'));
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
            'version=render-direct-20260603'
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
        const site = await input.sites.currentSite(ctx.from.id);
        if (!site) {
            await ctx.reply('未選擇項目。請先輸入 /site 項目名。');
            return;
        }
        const photo = ctx.message.photo.at(-1);
        if (!photo) {
            await ctx.reply('收不到相片。');
            return;
        }
        const existing = await input.db.photoByTelegramUniqueId(photo.file_unique_id);
        if (existing) {
            await ctx.reply(`這張相已上傳過：${existing.fileName}`);
            return;
        }
        const date = await currentRecordDate(input.db, ctx.from.id);
        await ctx.reply('收到相片，正在上傳到 Google Drive。');
        const dateFolderId = await input.sites.ensureDateFolder(site, date);
        const sequence = await input.db.nextPhotoSequence(site.id, date);
        const fileName = photoFileName(date, sequence);
        const fileUrl = await ctx.telegram.getFileLink(photo.file_id);
        const response = await fetch(fileUrl);
        if (!response.ok) {
            throw new Error(`Failed to download Telegram photo: ${response.status}`);
        }
        const uploaded = await input.google.uploadJpeg({
            folderId: dateFolderId,
            fileName,
            buffer: Buffer.from(await response.arrayBuffer())
        });
        await input.db.insertPhoto({
            siteId: site.id,
            userId: ctx.from.id,
            date,
            sequence,
            fileName,
            telegramFileUniqueId: photo.file_unique_id,
            driveFileId: uploaded.id,
            driveUrl: uploaded.url
        });
        await ctx.reply(`已上傳：${fileName}`);
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
async function setTelegramCommands(bot) {
    await bot.telegram.setMyCommands([
        { command: 'site', description: '新增或選擇項目' },
        { command: 'sites', description: '顯示全部項目' },
        { command: 'date', description: '設定記錄日期' },
        { command: 'status', description: '顯示目前狀態' },
        { command: 'report_now', description: '即時生成文件' },
        { command: 'debug', description: '顯示除錯資料' },
        { command: 'whoami', description: '顯示 Telegram user ID' }
    ]);
}
function commandText(text, command) {
    return text.replace(new RegExp(`^${command}(?:@\\S+)?\\s*`, 'i'), '').trim();
}
