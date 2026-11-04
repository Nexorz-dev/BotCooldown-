'use strict';

const COMMAND_FAMILIES = {
  work: ['k!work', 'k!w'],
  fish: ['k!fish', 'k!f'],
  mine: ['k!mine', 'k!m']
};

function getMessageText(message) {
  const embedText = (message.embeds || []).map(embed => [
    embed.title,
    embed.description,
    ...(embed.fields || []).flatMap(field => [field.name, field.value])
  ].filter(Boolean).join(' ')).join(' ');
  return `${message.content || ''} ${embedText}`.toLowerCase();
}

/**
 * Identify the command named by K-Bot's bilingual duplicate responses.
 * Markdown bold markers are optional because Discord may expose either
 * the raw message or the rendered embed text.
 */
function getRejectedCommandFamily(text) {
  const normalized = String(text || '').toLowerCase();
  const patterns = [
    { family: 'work', regex: /(?:you have already|you already)\s+\*{0,2}worked\*{0,2}\s+recently|ya has\s+\*{0,2}trabajado\*{0,2}\s+recientemente/ },
    { family: 'fish', regex: /(?:you have already|you already)\s+\*{0,2}fished\*{0,2}\s+recently|ya has\s+\*{0,2}pescado\*{0,2}\s+recientemente/ },
    { family: 'mine', regex: /(?:you have already|you already)\s+\*{0,2}mined\*{0,2}\s+recently|ya has\s+\*{0,2}minado\*{0,2}\s+recientemente/ }
  ];
  const match = patterns.find(item => item.regex.test(normalized));
  return match ? match.family : null;
}

function isRejectedCooldownMessage(text) {
  const normalized = String(text || '').toLowerCase();
  return (
    normalized.includes('you already claimed') ||
    normalized.includes('you already received') ||
    normalized.includes('you have already received') ||
    normalized.includes('you have already placed') ||
    normalized.includes('you recently received') ||
    normalized.includes("you've already claimed") ||
    (normalized.includes('please wait') &&
      normalized.includes('before using more commands')) ||
    getRejectedCommandFamily(normalized) !== null
  );
}

/** Match bot rejections without ever guessing between users in a shared channel. */
function findRejectedAttemptIndex(attempts, message, now = Date.now(), windowMs = 15000) {
  const text = getMessageText(message);
  const family = getRejectedCommandFamily(text);
  const eligible = attempts.filter(attempt =>
    attempt.channelId === message.channelId &&
    now >= attempt.startedAt &&
    now - attempt.startedAt < windowMs &&
    (!family || COMMAND_FAMILIES[family].includes(attempt.command))
  );
  if (eligible.length === 0) return -1;

  const referencedMessageId = message.reference?.messageId ||
    message.reference?.message_id || null;
  if (referencedMessageId) {
    return attempts.findIndex(attempt =>
      attempt.channelId === message.channelId &&
      attempt.messageId === referencedMessageId &&
      now >= attempt.startedAt &&
      now - attempt.startedAt < windowMs &&
      (!family || COMMAND_FAMILIES[family].includes(attempt.command))
    );
  }

  const mentions = [...new Set(text.match(/<@!?(\d+)>/g) || [])]
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

module.exports = {
  findRejectedAttemptIndex,
  getRejectedCommandFamily,
  isRejectedCooldownMessage
};
