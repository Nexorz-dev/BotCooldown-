'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { findRejectedAttemptIndex } = require('../pending-attempts');

function attempt(messageId, discordUserId, startedAt = 1000, channelId = 'channel-1') {
  return { messageId, discordUserId, startedAt, channelId };
}

test('a reply reference cancels only the attempt for that exact Discord message', () => {
  const attempts = [attempt('cmd-a', '111111111111111111'), attempt('cmd-b', '222222222222222222')];
  const message = { channelId: 'channel-1', reference: { messageId: 'cmd-b' }, content: 'please wait' };
  assert.equal(findRejectedAttemptIndex(attempts, message, 2000, 15000), 1);
});

test('a user mention identifies the matching user when only one attempt is pending for them', () => {
  const attempts = [attempt('cmd-a', '111111111111111111'), attempt('cmd-b', '222222222222222222')];
  const message = { channelId: 'channel-1', content: 'Please wait <@222222222222222222>' };
  assert.equal(findRejectedAttemptIndex(attempts, message, 2000, 15000), 1);
});

test('an ambiguous rejection in a shared channel cancels nobody', () => {
  const attempts = [attempt('cmd-a', '111111111111111111'), attempt('cmd-b', '222222222222222222')];
  const message = { channelId: 'channel-1', content: 'Please wait before using more commands.' };
  assert.equal(findRejectedAttemptIndex(attempts, message, 2000, 15000), -1);
});

test('an unreferenced rejection is not assigned to a pending attempt', () => {
  const attempts = [attempt('cmd-a', '111111111111111111')];
  const message = { channelId: 'channel-1', content: 'Please wait.' };
  assert.equal(findRejectedAttemptIndex(attempts, message, 2000, 15000), -1);
});

test('expired attempts are never cancelled', () => {
  const attempts = [attempt('cmd-a', '111111111111111111', 1000)];
  const message = { channelId: 'channel-1', content: 'Please wait.' };
  assert.equal(findRejectedAttemptIndex(attempts, message, 17000, 15000), -1);
});

test('multiple pending attempts by the mentioned user remain ambiguous', () => {
  const attempts = [attempt('cmd-a', '111111111111111111'), attempt('cmd-b', '111111111111111111')];
  const message = { channelId: 'channel-1', content: 'Please wait <@111111111111111111>' };
  assert.equal(findRejectedAttemptIndex(attempts, message, 2000, 15000), -1);
});
