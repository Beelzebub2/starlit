import assert from 'node:assert/strict';
import test from 'node:test';
import { readFileSync } from 'node:fs';
import { versionCode, versionParts, nextVersion, compareVersions, validateRelease } from './release-policy.mjs';

const notes = '## Starlit 1.1.0\n\n### Improvements\n\n- More reliable account synchronization.\n\n## Starlit 1.0.48\n\n- Previous release.\n';
const properties = 'VERSION_NAME=1.1.0\nVERSION_CODE=10100\n';

test('stable semver and monotonic Android build code', () => {
  assert.deepEqual(versionParts('v1.1.0'), [1, 1, 0]);
  assert.equal(versionCode('1.0.48'), 10048);
  assert.equal(versionCode('1.1.0'), 10100);
  assert.equal(versionCode('2.0.0'), 20000);
  assert.throws(() => versionCode('1.100.0'), /at most 99/);
  assert.throws(() => versionParts('v1.01.0'), /stable/);
  assert.throws(() => versionParts('1.1.0-rc.1'), /stable/);
});

test('major, minor and patch bumps keep semantic meaning', () => {
  assert.equal(nextVersion('1.0.48', 'major'), '2.0.0');
  assert.equal(nextVersion('1.0.48', 'minor'), '1.1.0');
  assert.equal(nextVersion('1.0.48', 'patch'), '1.0.49');
  assert.equal(compareVersions('1.1.0', '1.0.48'), 1);
  assert.equal(compareVersions('v1.0.9', 'v1.0.48'), -1);
  assert.throws(() => nextVersion('1.0.48', 'breaking'), /must be/);
});

test('release checks enforce tag, version code, notes and increasing version', () => {
  assert.deepEqual(validateRelease({ properties, notes, tag: 'v1.1.0', existingTags: ['v1.0.48'] }),
    { version: '1.1.0', code: 10100 });
  assert.throws(() => validateRelease({ properties, notes, tag: 'v1.0.49' }), /does not match/);
  assert.throws(() => validateRelease({ properties: properties.replace('10100', '10049'), notes, tag: 'v1.1.0' }), /VERSION_CODE/);
  assert.throws(() => validateRelease({ properties, notes, tag: 'v1.1.0', existingTags: ['v1.1.1'] }), /higher than/);
  assert.throws(() => validateRelease({ properties, notes: notes.replace('More reliable account synchronization.', 'TODO'), tag: 'v1.1.0' }), /placeholders/);
  assert.throws(() => validateRelease({ properties, notes: '## Starlit 1.0.48\n- Old.\n', tag: 'v1.1.0' }), /found 0/);
});

test('every published release requires a signed Android TV package and checksum', () => {
  const workflow = readFileSync(new URL('./workflows/windows-release.yml', import.meta.url), 'utf8');
  assert.match(workflow, /^  tv:$/m, 'TV release build must be a required job');
  assert.match(workflow, /:tvApp:assembleRelease/, 'TV release APK must be built, not reused from a debug run');
  assert.match(workflow, /name: starlit-tv-unsigned/, 'TV unsigned build must be handed to the signing job');
  assert.match(workflow, /needs: \[android, tv\]/, 'Android signing must fail closed when TV build fails');
  assert.match(workflow, /"\$tools\/apksigner" verify "\$tv"/, 'TV APK signature must be verified');
  assert.match(workflow, /name: starlit-tv-release/, 'Signed TV APK must be attached to publish inputs');
  assert.match(workflow, /SHA256SUMS-android\.txt SHA256SUMS-tv\.txt/, 'TV checksum must be included in release verification');
  assert.match(workflow, /"Starlit-TV-\$version\.apk"/, 'TV APK must be uploaded to the public release');
});

test('every normal release requires matching Tizen, webOS and TV installer preview artifacts', () => {
  const workflow = readFileSync(new URL('./workflows/windows-release.yml', import.meta.url), 'utf8');
  const tizen = readFileSync(new URL('./workflows/tizen.yml', import.meta.url), 'utf8');
  const webos = readFileSync(new URL('./workflows/webos.yml', import.meta.url), 'utf8');
  const installer = readFileSync(new URL('./workflows/webos-installer.yml', import.meta.url), 'utf8');
  const notice = readFileSync(new URL('../docs/TV-DEVELOPER-PREVIEW-NOTICE.md', import.meta.url), 'utf8');

  for (const [name, file, job] of [
    ['tizen-preview', tizen, 'tizen.yml'],
    ['webos-preview', webos, 'webos.yml'],
    ['tv-installer-preview', installer, 'webos-installer.yml'],
  ]) {
    const jobBody = workflow.split(`  ${name}:`)[1]?.split(/\r?\n  [a-z-]+:/)[0];
    assert.ok(jobBody, `${name} must be a tagged release job`);
    assert.ok(jobBody.includes(`uses: ./.github/workflows/${job}`),
      `${name} must invoke the expected reusable workflow`);
    assert.match(jobBody, /release_tag: \$\{\{ inputs\.release_tag \|\| github\.ref_name \}\}/,
      `${name} must build the exact release tag`);
    assert.match(file, /  workflow_call:/, `${job} must support tagged reusable builds`);
    assert.match(file, /required: true/, `${job} must require the release tag`);
    assert.match(file, /refs\/tags\/\{0\}/, `${job} must build the exact tagged source`);
  }
  assert.match(workflow, /needs: \[windows, android-sign, linux, tizen-preview, webos-preview, tv-installer-preview\]/,
    'release publication must fail if any TV preview build fails');
  for (const file of ['SHA256SUMS-Tizen', 'SHA256SUMS-WebOS', 'SHA256SUMS-TV-Installer']) {
    assert.match(workflow, new RegExp(file), `missing ${file} release checksum`);
  }
  for (const file of ['Starlit-Tizen-$version.wgt', 'Starlit-WebOS-$version.ipk',
    'Starlit-TV-Installer-Setup-Windows-$version.exe', 'Starlit-TV-Installer-Windows-$version.exe']) {
    assert.ok(workflow.includes(file), `missing tagged asset ${file}`);
  }
  assert.match(workflow, /pattern: starlit-\*-preview/, 'TV previews must be isolated from the stable artifact download');
  assert.match(workflow, /preview\/\*\.wgt/);
  assert.match(workflow, /preview\/\*\.ipk/);
  assert.match(workflow, /preview_tag="tv-preview-\$RELEASE_TAG"/);
  assert.match(workflow, /gh release create "\$preview_tag"/);
  assert.match(workflow, /--prerelease --latest=false/, 'TV preview must never become the latest stable release');
  assert.match(workflow, /--notes-file \.\.\/docs\/TV-DEVELOPER-PREVIEW-NOTICE\.md/);
  const stableAssets = workflow.split('gh "${args[@]}"')[1]?.split('# A separate prerelease tag')[0] || '';
  assert.doesNotMatch(stableAssets, /Starlit-(?:Tizen|WebOS|TV-Installer)-/,
    'unaccepted TV packages must not enter the stable release or its update channels');
  assert.match(notice, /developer previews/i);
  assert.match(notice, /not passed the physical-TV acceptance/i);
  assert.match(notice, /security\s+advisories/i);
  assert.match(tizen, /npm run verify:package/);
  assert.match(webos, /npm run verify:package:webos/);
  assert.match(installer, /SHA256SUMS-TV-Installer/);
  assert.match(installer, /npm test/);
});

test('Windows release uses pinned same-repository LFS runtimes and verifies both binaries', () => {
  const workflow = readFileSync(new URL('./workflows/windows-release.yml', import.meta.url), 'utf8');
  assert.match(workflow, /git lfs fetch origin "refs\/tags\/\$env:RELEASE_TAG"/,
    'release Windows job must retrieve exact tagged LFS objects from its own repository');
  assert.match(workflow, /07c68bb211f23a218ded0a36eb12207dc3aeb44e5318ffca6ce9dcc7c3173906/,
    'libmpv pinned SHA-256 must be checked');
  assert.match(workflow, /13031185a54abe0f729f9f3bdb7271a349a89ac8c559327d773c66007ed604f4/,
    'TorrServer pinned SHA-256 must be checked');
  assert.doesNotMatch(workflow, /git lfs fetch https?:\/\//,
    'release jobs must not depend on third-party Git LFS quotas');
});

test('Android TV pull requests compile and smoke-test a minified release build', () => {
  const workflow = readFileSync(new URL('./workflows/android-check.yml', import.meta.url), 'utf8');
  const smoke = readFileSync(new URL('./scripts/tv-emulator-smoke.sh', import.meta.url), 'utf8');
  assert.match(workflow, /^  tv-debug:$/m, 'debug TV package must be built in an isolated job');
  assert.match(workflow, /^  tv:$/m, 'optimized TV release must be a dedicated required job');
  assert.match(workflow, /\.\/gradlew :tvApp:assembleDebug\r?\n/, 'debug build must be available for testers');
  assert.match(workflow, /\.\/gradlew :tvApp:assembleRelease\r?\n/, 'release build must compile separately');
  assert.doesNotMatch(workflow, /:tvApp:assembleDebug :tvApp:assembleRelease/, 'both builds must not contend on the same runner');
  assert.match(workflow, /timeout-minutes: 60/, 'TV release CI must have a realistic packaging timeout');
  assert.match(workflow, /name: starlit-tv-android-release-unsigned/);
  assert.match(workflow, /^  tv-emulator:$/m);
  assert.match(workflow, /needs: tv/);
  assert.match(workflow, /script: bash \.github\/scripts\/tv-emulator-smoke\.sh/);
  assert.match(smoke, /apksigner" sign/);
  assert.match(smoke, /adb install -r "\$apk"/);
});
