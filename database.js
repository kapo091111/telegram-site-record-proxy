import pg from 'pg';
const { Pool } = pg;
export class Database {
    pool;
    constructor(databaseUrl) {
        this.pool = new Pool({
            connectionString: databaseUrl,
            ssl: databaseUrl.includes('render.com') || databaseUrl.includes('railway')
                ? { rejectUnauthorized: false }
                : undefined
        });
    }
    async migrate() {
        await this.pool.query(`
      create extension if not exists "pgcrypto";

      create table if not exists sites (
        id uuid primary key default gen_random_uuid(),
        user_id bigint not null,
        name text not null,
        drive_folder_id text,
        archived_at timestamptz,
        created_at timestamptz not null default now(),
        last_used_at timestamptz not null default now(),
        unique (user_id, name)
      );

      create table if not exists user_state (
        user_id bigint primary key,
        current_site_id uuid references sites(id),
        record_date text,
        updated_at timestamptz not null default now()
      );

      create table if not exists photos (
        id uuid primary key default gen_random_uuid(),
        site_id uuid not null references sites(id),
        user_id bigint not null,
        date text not null,
        sequence integer not null,
        file_name text not null,
        telegram_file_unique_id text,
        drive_file_id text not null,
        drive_url text,
        created_at timestamptz not null default now(),
        unique (site_id, date, sequence)
      );

      create table if not exists reports (
        id uuid primary key default gen_random_uuid(),
        site_id uuid not null references sites(id),
        user_id bigint not null,
        date text not null,
        drive_document_id text not null,
        drive_url text,
        created_at timestamptz not null default now(),
        unique (site_id, date)
      );

      alter table user_state add column if not exists record_date text;
      alter table photos add column if not exists telegram_file_unique_id text;
      create unique index if not exists photos_telegram_file_unique_id_idx
        on photos (telegram_file_unique_id)
        where telegram_file_unique_id is not null;
    `);
    }
    async upsertSite(userId, name) {
        const result = await this.pool.query(`
        insert into sites (user_id, name, archived_at, last_used_at)
        values ($1, $2, null, now())
        on conflict (user_id, name)
        do update set archived_at = null, last_used_at = now()
        returning *
      `, [userId, name]);
        return rowToSite(result.rows[0]);
    }
    async setCurrentSite(userId, siteId) {
        await this.pool.query(`
        insert into user_state (user_id, current_site_id, updated_at)
        values ($1, $2, now())
        on conflict (user_id)
        do update set current_site_id = excluded.current_site_id, updated_at = now()
      `, [userId, siteId]);
        await this.pool.query('update sites set last_used_at = now() where id = $1', [siteId]);
    }
    async setRecordDate(userId, date) {
        await this.pool.query(`
        insert into user_state (user_id, record_date, updated_at)
        values ($1, $2, now())
        on conflict (user_id)
        do update set record_date = excluded.record_date, updated_at = now()
      `, [userId, date]);
    }
    async currentRecordDate(userId) {
        const result = await this.pool.query('select record_date from user_state where user_id = $1', [userId]);
        return result.rows[0]?.record_date || null;
    }
    async currentSite(userId) {
        const result = await this.pool.query(`
        select s.*
        from user_state us
        join sites s on s.id = us.current_site_id
        where us.user_id = $1 and s.archived_at is null
      `, [userId]);
        return result.rows[0] ? rowToSite(result.rows[0]) : null;
    }
    async listSites(userId, limit = 10) {
        const result = await this.pool.query(`
        select *
        from sites
        where user_id = $1 and archived_at is null
        order by last_used_at desc
        limit $2
      `, [userId, limit]);
        return result.rows.map(rowToSite);
    }
    async setSiteDriveFolder(siteId, folderId) {
        await this.pool.query('update sites set drive_folder_id = $2 where id = $1', [siteId, folderId]);
    }
    async nextPhotoSequence(siteId, date) {
        const result = await this.pool.query('select coalesce(max(sequence), 0) + 1 as next_sequence from photos where site_id = $1 and date = $2', [siteId, date]);
        return Number(result.rows[0].next_sequence);
    }
    async insertPhoto(input) {
        const result = await this.pool.query(`
        insert into photos (site_id, user_id, date, sequence, file_name, telegram_file_unique_id, drive_file_id, drive_url)
        values ($1, $2, $3, $4, $5, $6, $7, $8)
        returning *
      `, [
            input.siteId,
            input.userId,
            input.date,
            input.sequence,
            input.fileName,
            input.telegramFileUniqueId,
            input.driveFileId,
            input.driveUrl
        ]);
        return rowToPhoto(result.rows[0]);
    }
    async photoByTelegramUniqueId(telegramFileUniqueId) {
        const result = await this.pool.query('select * from photos where telegram_file_unique_id = $1', [telegramFileUniqueId]);
        return result.rows[0] ? rowToPhoto(result.rows[0]) : null;
    }
    async photosForDate(siteId, date) {
        const result = await this.pool.query(`
        select *
        from photos
        where site_id = $1 and date = $2
        order by sequence asc
      `, [siteId, date]);
        return result.rows.map(rowToPhoto);
    }
    async sitesWithPhotosOnDate(date) {
        const result = await this.pool.query(`
        select distinct s.*
        from sites s
        join photos p on p.site_id = s.id
        where p.date = $1 and s.archived_at is null
        order by s.last_used_at desc
      `, [date]);
        return result.rows.map(rowToSite);
    }
    async upsertReport(input) {
        await this.pool.query(`
        insert into reports (site_id, user_id, date, drive_document_id, drive_url)
        values ($1, $2, $3, $4, $5)
        on conflict (site_id, date)
        do update set drive_document_id = excluded.drive_document_id, drive_url = excluded.drive_url, created_at = now()
      `, [input.siteId, input.userId, input.date, input.driveDocumentId, input.driveUrl]);
    }
}
function rowToSite(row) {
    return {
        id: row.id,
        userId: Number(row.user_id),
        name: row.name,
        driveFolderId: row.drive_folder_id,
        archivedAt: row.archived_at,
        createdAt: row.created_at,
        lastUsedAt: row.last_used_at
    };
}
function rowToPhoto(row) {
    return {
        id: row.id,
        siteId: row.site_id,
        userId: Number(row.user_id),
        date: row.date,
        sequence: Number(row.sequence),
        fileName: row.file_name,
        telegramFileUniqueId: row.telegram_file_unique_id,
        driveFileId: row.drive_file_id,
        driveUrl: row.drive_url,
        createdAt: row.created_at
    };
}
