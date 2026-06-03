import { dateFolderName, hkDate } from './date.js';
export class SiteService {
    db;
    google;
    constructor(db, google) {
        this.db = db;
        this.google = google;
    }
    async useSite(userId, name) {
        const cleanName = name.trim();
        if (!cleanName) {
            throw new Error('項目名不可留空');
        }
        const site = await this.db.upsertSite(userId, cleanName);
        const rootId = await this.google.ensureRootFolder();
        const siteFolderId = site.driveFolderId || (await this.google.ensureFolder(site.name, rootId));
        if (!site.driveFolderId) {
            await this.db.setSiteDriveFolder(site.id, siteFolderId);
            site.driveFolderId = siteFolderId;
        }
        await this.db.setCurrentSite(userId, site.id);
        return site;
    }
    async currentSite(userId) {
        return this.db.currentSite(userId);
    }
    async ensureDateFolder(site, date = hkDate()) {
        if (!site.driveFolderId) {
            const rootId = await this.google.ensureRootFolder();
            site.driveFolderId = await this.google.ensureFolder(site.name, rootId);
            await this.db.setSiteDriveFolder(site.id, site.driveFolderId);
        }
        return this.google.ensureFolder(dateFolderName(date), site.driveFolderId);
    }
}
