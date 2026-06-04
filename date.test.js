import { describe, expect, it } from 'vitest';
import { dateFolderName, documentName, normaliseDate, photoFileName } from './date.js';
describe('date helpers', () => {
    it('formats photo file names without separators', () => {
        expect(photoFileName('2026-06-03', 1)).toBe('20260603001.jpg');
        expect(photoFileName('2026-06-03', 12)).toBe('20260603012.jpg');
    });
    it('formats date folder names without separators', () => {
        expect(dateFolderName('2026-06-03')).toBe('20260603');
    });
    it('formats Google Docs document names without suffix', () => {
        expect(documentName('2026-06-03', '尖沙咀張生')).toBe('2026-06-03 - 尖沙咀張生');
    });
    it('normalises compact and dashed dates', () => {
        expect(normaliseDate('20260603')).toBe('2026-06-03');
        expect(normaliseDate('2026-06-03')).toBe('2026-06-03');
        expect(normaliseDate('20260231')).toBeNull();
    });
});
