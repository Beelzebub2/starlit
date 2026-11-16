#pragma once

#include <cstdint>
#include <functional>
#include <string>

namespace setup::tvdownload {

// A strict cap protects both disk space and the setup process when fetching an
// opt-in, unsigned developer-preview executable.
constexpr uint64_t kMaxPortableBytes = 256ull * 1024 * 1024;
constexpr uint64_t kMaxUnpackedArchiveBytes = 256ull * 1024 * 1024;

bool validReleaseVersion(const std::wstring& version);
std::wstring portableAssetName(const std::wstring& version);
std::wstring unpackedAssetName(const std::wstring& version);
std::wstring releaseAssetPath(const std::wstring& version, const std::wstring& file);
std::string checksumForAsset(const std::string& checksums, const std::string& asset);

// Fetches a version-matched preview from the official GitHub release over
// validated HTTPS, verifies its SHA-256 against the release checksum manifest,
// then atomically places it in the app's staging folder. Never runs the file.
// The caller owns staging cleanup on failure.
bool downloadPortableInstaller(
    const std::wstring& version,
    const std::wstring& destination,
    const std::function<void(uint64_t, uint64_t)>& onProgress,
    std::wstring& error);

// Fetches the SHA-256-verified archive of the already-unpacked Electron runtime.
// The archive is extracted in staging by the desktop setup; it is never executed.
bool downloadUnpackedInstaller(
    const std::wstring& version,
    const std::wstring& destination,
    const std::function<void(uint64_t, uint64_t)>& onProgress,
    std::wstring& error);

}  // namespace setup::tvdownload
