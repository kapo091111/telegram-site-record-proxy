import express from 'express';
import { dateFolderName, hkDate, mediaFileName, normaliseDate } from './date.js';
const MAX_UPLOAD_BYTES = 250 * 1024 * 1024;
export function configureMobileUpload(input) {
    const router = express.Router();
    router.use((req, res, next) => {
        if (!input.mobileAppKey) {
            res.status(503).json({ error: '未設定 MOBILE_APP_KEY。' });
            return;
        }
        const key = String(req.header('x-mobile-app-key') || req.query.key || '');
        if (key !== input.mobileAppKey) {
            res.status(401).json({ error: 'App key 不正確。' });
            return;
        }
        next();
    });
    router.get('/state', async (_req, res) => {
        const [currentSite, recordDate, remark, sites] = await Promise.all([
            input.sites.currentSite(input.ownerUserId),
            currentRecordDate(input.db, input.ownerUserId),
            input.db.currentFileRemark(input.ownerUserId),
            input.db.listSites(input.ownerUserId, 100)
        ]);
        res.json({
            currentSite,
            recordDate,
            remark,
            folderName: dateFolderName(recordDate, remark || ''),
            sites
        });
    });
    router.post('/sync-sites', async (_req, res) => {
        const result = await input.sites.syncSitesFromSheet(input.ownerUserId);
        const sites = await input.db.listSites(input.ownerUserId, 100);
        res.json({ ok: true, result, sites });
    });
    router.post('/delete-site', express.json({ limit: '1mb' }), async (req, res) => {
        const siteId = String(req.body?.siteId || '');
        const site = await input.db.siteById(input.ownerUserId, siteId);
        if (!site || site.archivedAt) {
            res.status(404).json({ error: '找不到地盤。' });
            return;
        }
        const result = await input.db.deleteSiteIfEmpty(input.ownerUserId, site.id);
        if (!result.deleted) {
            res.status(409).json({
                error: `這個地盤已有 ${result.fileCount} 個檔案，不能直接刪除；可以先用「完成地盤」隱藏。`
            });
            return;
        }
        const sites = await input.db.listSites(input.ownerUserId, 100);
        res.json({ ok: true, sites });
    });
    router.post('/upload', express.raw({ type: '*/*', limit: MAX_UPLOAD_BYTES }), async (req, res) => {
        const siteId = String(req.header('x-site-id') || req.query.siteId || '');
        const site = await input.db.siteById(input.ownerUserId, siteId);
        if (!site || site.archivedAt) {
            res.status(404).json({ error: '找不到地盤，或地盤已完成。' });
            return;
        }
        const date = normaliseDate(String(req.header('x-record-date') || req.query.date || '')) ||
            await currentRecordDate(input.db, input.ownerUserId);
        const remark = String(req.header('x-file-remark') || req.query.remark || '').trim();
        const mimeType = String(req.header('content-type') || req.header('x-mime-type') || 'application/octet-stream');
        const extension = cleanExtension(String(req.header('x-file-extension') || req.query.extension || extensionFromMime(mimeType) || 'bin'));
        const clientFileId = String(req.header('x-client-file-id') || req.query.clientFileId || '').trim() ||
            `mobile:${Date.now()}:${Math.random().toString(36).slice(2)}`;
        if (!Buffer.isBuffer(req.body) || req.body.length === 0) {
            res.status(400).json({ error: '沒有收到檔案內容。' });
            return;
        }
        const existing = await input.db.photoByTelegramUniqueId(clientFileId);
        if (existing) {
            res.json({
                ok: true,
                duplicate: true,
                fileName: existing.fileName,
                folderName: existing.folderName,
                synologyStatus: existing.synologyStatus
            });
            return;
        }
        const folderName = dateFolderName(date, remark);
        const folderId = await input.sites.ensureDateFolder(site, date, remark);
        const sequence = await input.db.nextPhotoSequence(site.id, date);
        const fileName = mediaFileName(date, sequence, extension);
        const uploaded = await input.google.uploadFile({
            folderId,
            fileName,
            mimeType,
            buffer: req.body
        });
        const record = await input.db.insertPhoto({
            siteId: site.id,
            userId: input.ownerUserId,
            date,
            sequence,
            fileName,
            folderName,
            telegramFileUniqueId: clientFileId,
            driveFileId: uploaded.id,
            driveUrl: uploaded.url
        });
        if (input.sync) {
            void input.sync.syncDate(date).catch((error) => console.error(error));
        }
        res.json({
            ok: true,
            duplicate: false,
            fileName: record.fileName,
            folderName: record.folderName,
            siteName: site.name,
            driveUrl: record.driveUrl,
            synologyStatus: record.synologyStatus
        });
    });
    input.app.use('/api/mobile', router);
}
async function currentRecordDate(db, userId) {
    return (await db.currentRecordDate(userId)) || hkDate();
}
function cleanExtension(value) {
    return value.replace(/^\./, '').replace(/[^a-zA-Z0-9]/g, '').toLowerCase().slice(0, 8) || 'bin';
}
function extensionFromMime(mimeType) {
    if (mimeType === 'image/jpeg')
        return 'jpg';
    if (mimeType === 'image/png')
        return 'png';
    if (mimeType === 'image/webp')
        return 'webp';
    if (mimeType === 'video/mp4')
        return 'mp4';
    if (mimeType === 'video/quicktime')
        return 'mov';
    if (mimeType === 'application/pdf')
        return 'pdf';
    return null;
}
