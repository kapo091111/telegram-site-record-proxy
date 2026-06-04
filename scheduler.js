import cron from 'node-cron';
export function scheduleDailyReports(input) {
    return cron.schedule('0 19 * * *', async () => {
        try {
            const reports = await input.reports.createReportsForToday();
            for (const report of reports) {
                for (const userId of input.allowedUserIds) {
                    await input.bot.telegram.sendMessage(userId, [`已生成文件：${report.site.name}`, report.documentUrl].join('\n'));
                }
            }
            if (input.sync) {
                const result = await input.sync.syncPendingDates();
                const uploaded = result.reduce((sum, item) => sum + item.uploaded, 0);
                for (const userId of input.allowedUserIds) {
                    await input.bot.telegram.sendMessage(userId, `已同步到 Synology：${result.length} 個地盤，${uploaded} 個檔案。`);
                }
            }
        }
        catch (error) {
            console.error(error);
            for (const userId of input.allowedUserIds) {
                await input.bot.telegram.sendMessage(userId, '出錯。請用 /debug 檢查狀態，或稍後再試。');
            }
        }
    }, {
        timezone: input.timezone
    });
}
