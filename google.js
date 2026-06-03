import { Readable } from 'node:stream';
import { OAuth2Client } from 'google-auth-library';
import { google } from 'googleapis';
import { documentName } from './date.js';
const FOLDER_MIME = 'application/vnd.google-apps.folder';
const DOC_MIME = 'application/vnd.google-apps.document';
export class GoogleWorkspace {
    drive;
    docs;
    rootFolderId;
    constructor(input) {
        const auth = new OAuth2Client(input.clientId, input.clientSecret);
        auth.setCredentials({ refresh_token: input.refreshToken });
        this.drive = google.drive({ version: 'v3', auth });
        this.docs = google.docs({ version: 'v1', auth });
        this.rootFolderId = input.rootFolderId;
    }
    async ensureRootFolder() {
        return this.rootFolderId;
    }
    async ensureFolder(name, parentId) {
        const q = [
            `name = '${escapeDriveQuery(name)}'`,
            `mimeType = '${FOLDER_MIME}'`,
            'trashed = false',
            `'${parentId}' in parents`
        ].join(' and ');
        const existing = await this.drive.files.list({
            q,
            fields: 'files(id, name)',
            spaces: 'drive',
            pageSize: 1,
            supportsAllDrives: true,
            includeItemsFromAllDrives: true
        });
        const found = existing.data.files?.[0]?.id;
        if (found) {
            return found;
        }
        const created = await this.drive.files.create({
            requestBody: {
                name,
                mimeType: FOLDER_MIME,
                parents: [parentId]
            },
            fields: 'id',
            supportsAllDrives: true
        });
        if (!created.data.id) {
            throw new Error(`Failed to create Drive folder: ${name}`);
        }
        return created.data.id;
    }
    async uploadJpeg(input) {
        const uploaded = await this.drive.files.create({
            requestBody: {
                name: input.fileName,
                parents: [input.folderId]
            },
            media: {
                mimeType: 'image/jpeg',
                body: Readable.from(input.buffer)
            },
            fields: 'id, webViewLink',
            supportsAllDrives: true
        });
        if (!uploaded.data.id) {
            throw new Error(`Failed to upload photo: ${input.fileName}`);
        }
        return {
            id: uploaded.data.id,
            url: uploaded.data.webViewLink || null
        };
    }
    async createDocument(input) {
        const name = documentName(input.date, input.siteName);
        const existing = await this.findFile(name, input.folderId, DOC_MIME);
        const documentId = existing?.id ||
            (await this.drive.files.create({
                requestBody: {
                    name,
                    mimeType: DOC_MIME,
                    parents: [input.folderId]
                },
                fields: 'id, webViewLink',
                supportsAllDrives: true
            })).data.id;
        if (!documentId) {
            throw new Error(`Failed to create Google Docs file: ${name}`);
        }
        await this.replaceDocumentText(documentId, input.content);
        return {
            id: documentId,
            url: existing?.url || `https://docs.google.com/document/d/${documentId}/edit`
        };
    }
    async findFile(name, parentId, mimeType) {
        const result = await this.drive.files.list({
            q: [
                `name = '${escapeDriveQuery(name)}'`,
                `mimeType = '${mimeType}'`,
                'trashed = false',
                `'${parentId}' in parents`
            ].join(' and '),
            fields: 'files(id, webViewLink)',
            spaces: 'drive',
            pageSize: 1,
            supportsAllDrives: true,
            includeItemsFromAllDrives: true
        });
        const file = result.data.files?.[0];
        return file?.id ? { id: file.id, url: file.webViewLink || null } : null;
    }
    async replaceDocumentText(documentId, content) {
        const document = await this.docs.documents.get({ documentId });
        const endIndex = document.data.body?.content?.at(-1)?.endIndex;
        const requests = [];
        if (endIndex && endIndex > 2) {
            requests.push({
                deleteContentRange: {
                    range: {
                        startIndex: 1,
                        endIndex: endIndex - 1
                    }
                }
            });
        }
        requests.push({
            insertText: {
                location: { index: 1 },
                text: content
            }
        });
        await this.docs.documents.batchUpdate({
            documentId,
            requestBody: { requests }
        });
    }
}
function escapeDriveQuery(value) {
    return value.replace(/\\/g, '\\\\').replace(/'/g, "\\'");
}
