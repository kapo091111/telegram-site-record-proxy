import { documentName } from './date.js';
export function buildReportText(input) {
    const lines = [
        `${input.date}　${input.siteName}`,
        '',
        `檔案：${input.photos.length} 個`,
        '',
        '檔案記錄'
    ];
    if (input.photos.length === 0) {
        lines.push('－ 今日未有檔案記錄');
    }
    else {
        for (const photo of input.photos) {
            lines.push(`－ ${photo.fileName}`);
        }
    }
    return lines.join('\n');
}
export function buildReportSummary(date, siteName, documentUrl) {
    return ['已生成文件：', documentName(date, siteName), '', documentUrl].join('\n');
}
