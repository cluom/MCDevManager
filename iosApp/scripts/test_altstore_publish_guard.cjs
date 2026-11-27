const {test} = require('node:test');
const assert = require('node:assert/strict');
const {readFileSync} = require('node:fs');
const {ensureRelease, validateBuild} = require('./altstore_publish_guard.cjs');

const sha = 'a'.repeat(40);
const run = {repository: {full_name: 'cluom/MCDevManager'},
  path: '.github/workflows/ios.yml', head_sha: sha, conclusion: 'failure'};
const job = {name: 'ios', status: 'completed', conclusion: 'success', head_sha: sha};
const fail = status => Object.assign(new Error(`HTTP ${status}`), {status});
function fixture({missing = false, createStatus, draft = false, targetSha = sha, annotated = false} = {}) {
  const calls = [];
  const release = {draft, html_url: 'https://github.com/cluom/MCDevManager/releases/tag/altstore-ios-123'};
  const github = {rest: {repos: {
    getReleaseByTag: async () => {if (missing) throw fail(404); return {data: release};},
    createRelease: async args => {calls.push(args); if (createStatus) throw fail(createStatus); return {data: release};},
  }, git: {
    getRef: async () => ({data: {object: {type: annotated ? 'tag' : 'commit', sha: targetSha}}}),
    getTag: async () => ({data: {object: {type: 'commit', sha: targetSha}}}),
  }}};
  return {args: {github, repo: {owner: 'cluom', repo: 'MCDevManager'}, runId: '123', sha}, calls};
}

test('publication failure does not invalidate successful compilation', () => {
  validateBuild(run, [job, {name: 'publish-altstore / publish', conclusion: 'failure'}]);
});
test('in-progress parent is allowed after compilation success', () => {
  validateBuild({...run, conclusion: null}, [job]);
});
for (const conclusion of ['failure', 'cancelled', 'skipped', null]) {
  test(`reject unsuccessful compilation: ${conclusion}`, () => {
    assert.throws(() => validateBuild(run, [{...job, conclusion}]), /compilation/);
  });
}
test('reject absent, duplicate, running, wrong-source jobs and foreign workflows', () => {
  for (const jobs of [[], [job, job], [{...job, status: 'in_progress'}], [{...job, head_sha: 'b'}]]) {
    assert.throws(() => validateBuild(run, jobs));
  }
  assert.throws(() => validateBuild({...run, path: '.github/workflows/other.yml'}, [job]));
  assert.throws(() => validateBuild({...run, repository: {full_name: 'other/repo'}}, [job]));
});
test('existing release is checked, never recreated', async () => {
  const {args, calls} = fixture();
  await ensureRelease(args);
  assert.equal(calls.length, 0);
});
test('missing release prepared at exact source without marking latest', async () => {
  const {args, calls} = fixture({missing: true});
  await ensureRelease(args);
  assert.equal(calls[0].target_commitish, sha);
  assert.equal(calls[0].tag_name, 'altstore-ios-123');
  assert.equal(calls[0].make_latest, 'false');
  assert.equal(calls[0].draft, false);
});
for (const createStatus of [403, 422]) {
  test(`permission failure ${createStatus} provides exact manual recovery URL`, async () => {
    const {args} = fixture({missing: true, createStatus});
    await assert.rejects(ensureRelease(args), new RegExp(`releases/new\\?tag=altstore-ios-123&target=${sha}`));
  });
}
test('server errors are preserved, not described as missing permissions', async () => {
  const {args} = fixture({missing: true, createStatus: 500});
  await assert.rejects(ensureRelease(args), /HTTP 500/);
});
test('reject drafts and mismatched source without moving tags', async () => {
  await assert.rejects(ensureRelease(fixture({draft: true}).args), /draft/);
  await assert.rejects(ensureRelease(fixture({targetSha: 'b'}).args), /original build source/);
  await ensureRelease(fixture({annotated: true}).args);
});
test('concurrent creation is safe when the release appears on reread', async () => {
  const {args} = fixture({missing: true, createStatus: 422});
  let reads = 0;
  args.github.rest.repos.getReleaseByTag = async () => {
    if (++reads === 1) throw fail(404);
    return {data: {draft: false}};
  };
  await ensureRelease(args);
});
test('workflow gates compilation and checks latest jobs on publication-only retries', () => {
  const ios = readFileSync('.github/workflows/ios.yml', 'utf8');
  assert.match(ios, /ios:\s+needs: prepare-release/);
  assert.match(ios, /needs\.prepare-release\.result == 'success'/);
  const publish = readFileSync('.github/workflows/altstore-publish.yml', 'utf8');
  assert.match(publish, /filter: 'latest'/);
  assert.match(publish, /validateBuild\(run, jobs\)/);
  assert.doesNotMatch(publish, /run\.conclusion !== 'success'/);
});
