'use strict';

/** Match bot rejections without ever guessing between users in a shared channel. */
function findRejectedAttemptIndex(attempts, message, now = Date.now(), windowMs = 15000) {
  const eligible = attempts.filter(attempt =>
    attempt.channelId === message.channelId &&
    now >= attempt.startedAt &&
    now - attempt.startedAt < windowMs
  );
  if (eligible.length === 0) return -1;

  const referencedMessageId = message.reference?.messageId ||
    message.reference?.message_id || null;
  if (referencedMessageId) {
    return attempts.findIndex(attempt =>
      attempt.channelId === message.channelId &&
      attempt.messageId === referencedMessageId &&
      now >= attempt.startedAt &&
      now - attempt.startedAt < windowMs
    );
  }

  const text = [message.content || ''];
  for (const embed of message.embeds || []) {
    text.push(embed.title || '', embed.description || '');
    for (const field of embed.fields || []) text.push(field.name || '', field.value || '');
  }
  const mentions = [...new Set(text.join(' ').match(/<@!?(\d+)>/g) || [])]
    .map(value => value.match(/\d+/)[0]);

  if (mentions.length === 1) {
    const matchingUser = eligible.filter(attempt => attempt.discordUserId === mentions[0]);
    return matchingUser.length === 1 ? attempts.indexOf(matchingUser[0]) : -1;
  }

  // Without a reply reference or an explicit author mention, the response
  // cannot be attributed safely. This also protects against unlinked users
  // issuing commands in the same channel.
  return -1;
}

module.exports = { findRejectedAttemptIndex };
