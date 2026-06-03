import cron from 'node-cron';
export function scheduleDailyReports(input) {
    return cron.schedule('0 22 * * *', async () => {
        try {
            const reports = await input.reports.createReportsForToday();
            for (const report of reports) {
                for (const userId of input.allowedUserIds) {
                    await input.bot.telegram.sendMessage(userId, [`已自動生成文件：${report.site.name}`, report.documentUrl].join('\n'));
                }
            }
        }
        catch (error) {
            console.error(error);
            for (const userId of input.allowedUserIds) {
                await input.bot.telegram.sendMessage(userId, '自動生成文件時出錯，請用 /report_now 再試。');
            }
        }
    }, {
        scheduled: true,
        timezone: input.timezone
    });
}
