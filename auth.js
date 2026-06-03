export function isAllowedUser(userId, allowedIds) {
    return typeof userId === 'number' && allowedIds.includes(userId);
}
export function requireAllowedUser(allowedIds) {
    return async (ctx, next) => {
        const userId = ctx.from?.id;
        if (!isAllowedUser(userId, allowedIds)) {
            await ctx.reply('你沒有權限使用這個 bot。');
            return;
        }
        return next();
    };
}
