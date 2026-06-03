import { buildReportSummary, buildReportText } from './report.js';
import { hkDate } from './date.js';
export class ReportService {
    db;
    google;
    sites;
    constructor(db, google, sites) {
        this.db = db;
        this.google = google;
        this.sites = sites;
    }
    async createReportForSite(site, date = hkDate()) {
        const photos = await this.db.photosForDate(site.id, date);
        const dateFolderId = await this.sites.ensureDateFolder(site, date);
        const content = buildReportText({
            date,
            siteName: site.name,
            photos: photos.map((photo) => ({
                fileName: photo.fileName,
                driveUrl: photo.driveUrl
            }))
        });
        const document = await this.google.createDocument({
            folderId: dateFolderId,
            date,
            siteName: site.name,
            content
        });
        await this.db.upsertReport({
            siteId: site.id,
            userId: site.userId,
            date,
            driveDocumentId: document.id,
            driveUrl: document.url
        });
        return {
            documentUrl: document.url,
            message: buildReportSummary(date, site.name, document.url || '')
        };
    }
    async createReportsForToday() {
        const date = hkDate();
        const sites = await this.db.sitesWithPhotosOnDate(date);
        const results = [];
        for (const site of sites) {
            const report = await this.createReportForSite(site, date);
            results.push({ site, documentUrl: report.documentUrl });
        }
        return results;
    }
}
