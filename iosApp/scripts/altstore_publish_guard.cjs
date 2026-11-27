// Shared by the pre-build gate and publication-only retries. No elevated token required.
async function ensureRelease({github, repo, runId, sha}) {
  const tag = `altstore-ios-${runId}`;
  let release;
  try {
    release = (await github.rest.repos.getReleaseByTag({...repo, tag})).data;
  } catch (error) {
    if (error.status !== 404) throw error;
    try {
      release = (await github.rest.repos.createRelease({...repo, tag_name: tag,
        target_commitish: sha, name: `MCDevManager iOS · ${runId}`,
        body: '非上游官方定制版。发布准备已完成，IPA 尚未上传与校验；未进行真机验证。',
        draft: false, prerelease: false, make_latest: 'false'})).data;
    } catch (creationError) {
      if (![403, 422].includes(creationError.status)) throw creationError;
      // Another attempt may have prepared the same release concurrently.
      try {
        release = (await github.rest.repos.getReleaseByTag({...repo, tag})).data;
      } catch (readError) {
        if (readError.status !== 404) throw readError;
        const url = `https://github.com/${repo.owner}/${repo.repo}/releases/new?tag=${tag}&target=${sha}`;
        throw new Error(`Release preparation blocked (HTTP ${creationError.status}). ` +
          `Use your GitHub account to create a public Release: ${url} . ` +
          `Tag must point to ${sha}. Then rerun the failed jobs; do not restart a successful iOS build. ` +
          'No token permissions or repository settings were changed.');
      }
    }
  }
  if (release.draft) throw new Error(`Publish the prepared draft Release: ${release.html_url}`);
  let target = (await github.rest.git.getRef({...repo, ref: `tags/${tag}`})).data.object;
  const visited = new Set();
  while (target.type === 'tag') {
    if (visited.has(target.sha)) throw new Error('Invalid cyclic release tag');
    visited.add(target.sha);
    target = (await github.rest.git.getTag({...repo, tag_sha: target.sha})).data.object;
  }
  if (target.type !== 'commit' || target.sha !== sha) {
    throw new Error('Release tag does not point to the original build source; refusing to move it');
  }
  return release;
}

function validateBuild(run, jobs) {
  if (run.repository?.full_name !== 'cluom/MCDevManager' || run.path !== '.github/workflows/ios.yml') {
    throw new Error('Only this repository\'s independent iOS workflow is allowed');
  }
  // Overall conclusion includes publication. Its failure must not invalidate a good IPA.
  // Callers request filter=latest so a newer failed build cannot reuse a stale success.
  const builds = jobs.filter(job => job.name === 'ios');
  if (builds.length !== 1 || builds[0].status !== 'completed' || builds[0].conclusion !== 'success') {
    throw new Error('The iOS compilation job did not complete successfully');
  }
  if (builds[0].head_sha !== run.head_sha) throw new Error('Build job source SHA mismatch');
}

module.exports = {ensureRelease, validateBuild};
