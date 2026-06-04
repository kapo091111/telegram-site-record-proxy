import Client from 'ssh2-sftp-client';
import { compactDate, documentName } from './date.js';
export class SftpSyncService {
    db;
    google;
    config;
    constructor(db, google, config) {
        this.db = db;
        this.google = google;
        this.config = config;
    }
    async syncDate(date) {
        const sites = await this.db.sitesWithPendingFilesOnDate(date);
        const results = [];
        for (const site of sites) {
            const uploaded = await this.syncSiteDate(site, date);
            results.push({ site, uploaded });
        }
        return results;
    }
    async syncPendingDates() {
        const dates = await this.db.pendingDates();
        const results = [];
        for (const date of dates) {
            const dateResults = await this.syncDate(date);
            for (const result of dateResults) {
                results.push({ date, ...result });
            }
        }
        return results;
    }
    async syncSiteDate(site, date) {
        const sftp = new Client();
        await sftp.connect({
            host: this.config.host,
            port: this.config.port,
            username: this.config.username,
            password: this.config.password,
            readyTimeout: 30_000
        });
        try {
            const dateDir = joinRemotePath(this.config.remoteRoot, safePathSegment(site.name), compactDate(date));
            await ensureRemoteDirectory(sftp, dateDir);
            let uploaded = 0;
            const photos = await this.db.pendingPhotosForDate(site.id, date);
            for (const photo of photos) {
                const remotePath = joinRemotePath(dateDir, safePathSegment(photo.fileName));
                try {
                    const buffer = await this.google.downloadFile(photo.driveFileId);
                    await sftp.put(buffer, remotePath);
                    await this.db.markPhotoSynced(photo.id, remotePath);
                    await this.deleteDriveTemporaryFile(photo.id, photo.driveFileId, photo.driveDeletedAt);
                    uploaded += 1;
                }
                catch (error) {
                    console.error(error);
                    await this.db.markPhotoSyncFailed(photo.id);
                }
            }
            const reports = await this.db.reportsForDate(site.id, date);
            for (const report of reports) {
                const buffer = await this.google.exportDocumentPdf(report.driveDocumentId);
                const fileName = `${documentName(date, site.name)}.pdf`;
                await sftp.put(buffer, joinRemotePath(dateDir, safePathSegment(fileName)));
                uploaded += 1;
            }
            return uploaded;
        }
        finally {
            await sftp.end();
        }
    }
    async deleteDriveTemporaryFile(photoId, driveFileId, driveDeletedAt) {
        if (driveDeletedAt) {
            return;
        }
        try {
            await this.google.deleteFile(driveFileId);
            await this.db.markPhotoDriveDeleted(photoId);
        }
        catch (error) {
            console.error(error);
        }
    }
}
async function ensureRemoteDirectory(sftp, remotePath) {
    const exists = await sftp.exists(remotePath);
    if (!exists) {
        await sftp.mkdir(remotePath, true);
    }
}
function joinRemotePath(...parts) {
    return parts
        .map((part, index) => {
        const trimmed = part.replace(/^\/+|\/+$/g, '');
        return index === 0 && part.startsWith('/') ? `/${trimmed}` : trimmed;
    })
        .filter(Boolean)
        .join('/');
}
function safePathSegment(value) {
    return value
        .trim()
        .replace(/[\\/:*?"<>|]/g, '_')
        .replace(/\s+/g, ' ');
}
