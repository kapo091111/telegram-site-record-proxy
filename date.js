export function hkDate(now = new Date()) {
    return new Intl.DateTimeFormat('en-CA', {
        timeZone: 'Asia/Hong_Kong',
        year: 'numeric',
        month: '2-digit',
        day: '2-digit'
    }).format(now);
}
export function compactDate(date) {
    return date.replace(/-/g, '');
}
export function normaliseDate(input) {
    const value = input.trim();
    const compact = value.match(/^(\d{4})(\d{2})(\d{2})$/);
    if (compact) {
        return validDate(`${compact[1]}-${compact[2]}-${compact[3]}`);
    }
    const dashed = value.match(/^(\d{4})-(\d{2})-(\d{2})$/);
    if (dashed) {
        return validDate(`${dashed[1]}-${dashed[2]}-${dashed[3]}`);
    }
    return null;
}
export function dateFolderName(date) {
    return compactDate(date);
}
export function photoFileName(date, sequence) {
    return `${compactDate(date)}${String(sequence).padStart(3, '0')}.jpg`;
}
export function mediaFileName(date, sequence, extension) {
    const cleanExtension = extension.replace(/^\./, '').toLowerCase();
    return `${compactDate(date)}${String(sequence).padStart(3, '0')}.${cleanExtension}`;
}
export function documentName(date, siteName) {
    return `${date} - ${siteName}`;
}
function validDate(date) {
    const parsed = new Date(`${date}T00:00:00.000Z`);
    if (Number.isNaN(parsed.getTime())) {
        return null;
    }
    const rebuilt = [
        parsed.getUTCFullYear(),
        String(parsed.getUTCMonth() + 1).padStart(2, '0'),
        String(parsed.getUTCDate()).padStart(2, '0')
    ].join('-');
    return rebuilt === date ? date : null;
}
