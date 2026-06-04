import { dateFolderName, hkDate } from './date.js';
export class SiteService {
    db;
    google;
    sheetConfig;
    constructor(db, google, sheetConfig = null) {
        this.db = db;
        this.google = google;
        this.sheetConfig = sheetConfig;
    }
    async useSite(userId, name) {
        const cleanName = name.trim();
        if (!cleanName) {
            throw new Error('地盤名不能為空。');
        }
        if (/^\d{4,}$/.test(cleanName)) {
            const existing = await this.db.findSiteByCode(userId, cleanName);
            if (existing) {
                await this.db.setCurrentSite(userId, existing.id);
                return existing;
            }
        }
        const site = await this.db.upsertSite(userId, cleanName);
        await this.appendNewSiteToSheet(cleanName);
        await this.ensureSiteFolder(site);
        await this.db.setCurrentSite(userId, site.id);
        return site;
    }
    async currentSite(userId) {
        return this.db.currentSite(userId);
    }
    async ensureDateFolder(site, date = hkDate(), remark = '') {
        await this.ensureSiteFolder(site);
        return this.google.ensureFolder(dateFolderName(date, remark), site.driveFolderId);
    }
    async syncSitesFromSheet(userId) {
        if (!this.sheetConfig) {
            throw new Error('未設定 Google Sheet 地盤清單。');
        }
        const sheetSites = await this.google.readSitesSheet(this.sheetConfig);
        let active = 0;
        let archived = 0;
        for (const sheetSite of sheetSites) {
            const isArchived = isArchivedStatus(sheetSite.status);
            await this.db.upsertSiteFromSheet(userId, sheetSite.code, sheetSite.name, isArchived);
            if (isArchived) {
                archived += 1;
            }
            else {
                active += 1;
            }
        }
        return { active, archived };
    }
    async ensureSiteFolder(site) {
        if (site.driveFolderId) {
            return;
        }
        const rootId = await this.google.ensureRootFolder();
        site.driveFolderId = await this.google.ensureFolder(site.name, rootId);
        await this.db.setSiteDriveFolder(site.id, site.driveFolderId);
    }
    async appendNewSiteToSheet(rawName) {
        if (!this.sheetConfig) {
            return;
        }
        const parsed = parseSiteName(rawName);
        if (!parsed.siteCode || !parsed.name) {
            return;
        }
        try {
            await this.google.appendSiteToSheet({
                ...this.sheetConfig,
                code: parsed.siteCode,
                name: parsed.name
            });
        }
        catch (error) {
            console.error(error);
        }
    }
}
function parseSiteName(value) {
    const match = value.trim().match(/^(\d{4,})[_\s-]*(.+)$/);
    if (!match) {
        return { siteCode: null, name: value.trim() };
    }
    return {
        siteCode: match[1],
        name: match[2].trim()
    };
}
function isArchivedStatus(status) {
    const value = status.trim().toLowerCase();
    return ['完成', '已完成', 'archive', 'archived', 'completed', 'done', 'hidden', 'hide'].includes(value);
}
