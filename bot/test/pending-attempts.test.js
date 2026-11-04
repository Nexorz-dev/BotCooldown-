'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { findRejectedAttemptIndex } = require('../pending-attempts');

function attempt(messageId, discordUserId, startedAt = 1000, channelId = 'channel-1') {
  return { messageId, discordUserId, startedAt, channelId };
}

test('a reply reference cancels only the attempt for that exact Discord message', () => {
  const attempts = [attempt('cmd-a', 'user-a'), attempt('cmd-b', 'user-b')];
  const message = { channelId: 'channel-1', reference: { messageId: 'cmd-b' }, content: 'please wait' };
  assert.equal(findRejectedAttemptIndex(attempts, message, 2000, 15000), 1);
});

test('a user mention identifies the matching user when only one attempt is pending for them', () => {
  const attempts = [attempt('cmd-a', 'user-a'), attempt('cmd-b', 'user-b')];
  const message = { channelId: 'channel-1', content: 'Please wait <@user-b>' };
  assert.equal(findRejectedAttemptIndex(attempts, message, 2000, 15000), 1);
});

test('an ambiguous rejection in a shared channel cancels nobody', () => {
  const attempts = [attempt('cmd-a', 'user-a'), attempt('cmd-b', 'user-b')];
  const message = { channelId: 'channel-1', content: 'Please wait before using more commands.' };
  assert.equal(findRejectedAttemptIndex(attempts, message, 2000, 15000), -1);
});

test('a non-referenced rejection can be matched when exactly one attempt exists', () => {
  const attempts = [attempt('cmd-a', 'user-a')];
  const message = { channelId: 'channel-1', content: 'Please wait.' };
  assert.equal(findRejectedAttemptIndex(attempts, message, 2000, 15000), 0);
});

test('expired attempts are never cancelled', () => {
  const attempts = [attempt('cmd-a', 'user-a', 1000)];
  const message = { channelId: 'channel-1', content: 'Please wait.' };
  assert.equal(findRejectedAttemptIndex(attempts, message, 17000, 15000), -1);
});

test('multiple pending attempts by the mentioned user remain ambiguous', () => {
  const attempts = [attempt('cmd-a', 'user-a'), attempt('cmd-b', 'user-a')];
  const message = { channelId: 'channel-1', content: 'Please wait <@user-a>' };
  assert.equal(findRejectedAttemptIndex(attempts, message, 2000, 15000), -1);
});
