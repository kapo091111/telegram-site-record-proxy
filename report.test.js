import { describe, expect, it } from 'vitest';
import { buildReportText } from './report.js';
describe('report text', () => {
    it('builds a clean photo-only report', () => {
        const report = buildReportText({
            date: '2026-06-03',
            siteName: '尖沙咀張生',
            photos: [
                { fileName: '20260603001.jpg', driveUrl: null },
                { fileName: '20260603002.jpg', driveUrl: null }
            ]
        });
        expect(report).toContain('2026-06-03　尖沙咀張生');
        expect(report).toContain('檔案：2 個');
        expect(report).toContain('－ 20260603001.jpg');
        expect(report).not.toContain('OpenAI');
        expect(report).not.toContain('現場進度');
    });
});
