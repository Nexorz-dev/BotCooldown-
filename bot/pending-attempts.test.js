'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const {
  findRejectedAttemptIndex,
  getRejectedCommandFamily,
  isRejectedCooldownMessage,
  normalizeCooldownCommand
} = require('./pending-attempts');

test('detects WORK duplicate rejection in Spanish and English', () => {
  assert.equal(getRejectedCommandFamily('Ya has **TRABAJADO** recientemente.'), 'work');
  assert.equal(getRejectedCommandFamily('You have already **WORKED** recently.'), 'work');
});

test('detects FISH duplicate rejection in Spanish and English', () => {
  assert.equal(getRejectedCommandFamily('Ya has **PESCADO** recientemente.'), 'fish');
  assert.equal(getRejectedCommandFamily('You have already **FISHED** recently.'), 'fish');
});

test('detects MINE duplicate rejection in Spanish and English', () => {
  assert.equal(getRejectedCommandFamily('Ya has **MINADO** recientemente.'), 'mine');
  assert.equal(getRejectedCommandFamily('You have already **MINED** recently.'), 'mine');
});

test('keeps the existing generic rejection phrases', () => {
  assert.equal(isRejectedCooldownMessage('Please wait 4.9 minutes before using more commands.'), true);
  assert.equal(isRejectedCooldownMessage('You already claimed this.'), true);
});

test('matches a duplicate rejection only to its command family', () => {
  const attempts = [
    { command: 'k!work', channelId: 'c1', messageId: 'm-work', discordUserId: 'u1', startedAt: 1000 },
    { command: 'k!fish', channelId: 'c1', messageId: 'm-fish', discordUserId: 'u1', startedAt: 1000 },
    { command: 'k!mine', channelId: 'c1', messageId: 'm-mine', discordUserId: 'u1', startedAt: 1000 }
  ];
  const message = {
    channelId: 'c1',
    content: 'Ya has **PESCADO** recientemente.',
    embeds: [],
    reference: { messageId: 'm-fish' }
  };
  assert.equal(findRejectedAttemptIndex(attempts, message, 2000, 15000), 1);
});

test('does not cancel an unrelated command when the rejection names another command', () => {
  const attempts = [
    { command: 'k!work', channelId: 'c1', messageId: 'm-work', discordUserId: 'u1', startedAt: 1000 }
  ];
  const message = {
    channelId: 'c1',
    content: 'You have already **MINED** recently.',
    embeds: [],
    reference: { messageId: 'm-work' }
  };
  assert.equal(findRejectedAttemptIndex(attempts, message, 2000, 15000), -1);
});

test('does not guess the user when a rejection has no reply reference or mention', () => {
  const attempts = [
    { command: 'k!work', channelId: 'c1', messageId: 'm-work', discordUserId: 'u1', startedAt: 1000 }
  ];
  const message = {
    channelId: 'c1',
    content: 'Ya has **TRABAJADO** recientemente.',
    embeds: []
  };
  assert.equal(findRejectedAttemptIndex(attempts, message, 2000, 15000), -1);
});

test('normalizes capitalization and accidental spaces for every integrated command and alias', () => {
  const commands = [
    'k!drop', 'k!d', 'k!daily', 'k!weekly',
    'k!megadrop', 'k!md', 'k!lucky card', 'k!l',
    'k!album drop', 'k!ad', 'k!album megadrop', 'k!amd',
    'k!stamp drop', 'k!sd', 'k!mine', 'k!m',
    'k!fish', 'k!f', 'k!work', 'k!w'
  ];

  for (const command of commands) {
    const spacedAndUppercase = command.replace(/^k!/, 'K! ');
    assert.equal(normalizeCooldownCommand(spacedAndUppercase), command);
    assert.equal(normalizeCooldownCommand(command.toUpperCase()), command);
  }
});

test('normalizes repeated spaces without merging multi-word command names', () => {
  assert.equal(normalizeCooldownCommand('  K!   Lucky   Card  '), 'k! lucky card');
  assert.equal(normalizeCooldownCommand('K!  Album   MegaDrop'), 'k! album megadrop');
  assert.equal(normalizeCooldownCommand('K!   M'), 'k! m');
});
