#include "tv_installer_download.h"

#include <windows.h>
#include <wincrypt.h>
#include <winhttp.h>

#include <algorithm>
#include <array>
#include <cctype>
#include <cstdio>
#include <functional>
#include <string>
#include <vector>

namespace setup::tvdownload {
namespace {

struct HttpHandle {
    HINTERNET handle = nullptr;
    explicit HttpHandle(HINTERNET value) : handle(value) {}
    ~HttpHandle() { if (handle) WinHttpCloseHandle(handle); }
    HttpHandle(const HttpHandle&) = delete;
    HttpHandle& operator=(const HttpHandle&) = delete;
};

struct FileHandle {
    HANDLE handle = INVALID_HANDLE_VALUE;
    explicit FileHandle(HANDLE value) : handle(value) {}
    ~FileHandle() { if (handle != INVALID_HANDLE_VALUE) CloseHandle(handle); }
    FileHandle(const FileHandle&) = delete;
    FileHandle& operator=(const FileHandle&) = delete;
    bool valid() const { return handle != INVALID_HANDLE_VALUE; }
    void close() {
        if (valid()) CloseHandle(handle);
        handle = INVALID_HANDLE_VALUE;
    }
};

struct Sha256 {
    HCRYPTPROV provider = 0;
    HCRYPTHASH hash = 0;
    ~Sha256() {
        if (hash) CryptDestroyHash(hash);
        if (provider) CryptReleaseContext(provider, 0);
    }
    bool init() {
        return CryptAcquireContextW(&provider, nullptr, nullptr, PROV_RSA_AES, CRYPT_VERIFYCONTEXT) &&
            CryptCreateHash(provider, CALG_SHA_256, 0, 0, &hash);
    }
    bool update(const uint8_t* data, DWORD length) {
        return CryptHashData(hash, data, length, 0) != FALSE;
    }
    std::string finish() {
        std::array<BYTE, 32> digest{};
        DWORD length = static_cast<DWORD>(digest.size());
        if (!CryptGetHashParam(hash, HP_HASHVAL, digest.data(), &length, 0) || length != digest.size()) return {};
        constexpr char hex[] = "0123456789abcdef";
        std::string result;
        result.reserve(digest.size() * 2);
        for (const BYTE b : digest) {
            result += hex[b >> 4];
            result += hex[b & 15];
        }
        return result;
    }
};

constexpr uint64_t kMaxManifestBytes = 64 * 1024;
constexpr wchar_t kReleaseHost[] = L"github.com";
constexpr wchar_t kUserAgent[] = L"Starlit-Windows-Setup/1.0";

// Callback returning false stops the request without accepting partial data.
bool httpsGet(const std::wstring& path, uint64_t limit,
              const std::function<bool(const uint8_t*, DWORD, uint64_t, uint64_t)>& write,
              std::wstring& error) {
    HttpHandle session(WinHttpOpen(kUserAgent, WINHTTP_ACCESS_TYPE_AUTOMATIC_PROXY,
                                  WINHTTP_NO_PROXY_NAME, WINHTTP_NO_PROXY_BYPASS, 0));
    if (!session.handle) {
        error = L"Windows couldn't initialize an HTTPS download.";
        return false;
    }
    WinHttpSetTimeouts(session.handle, 10000, 10000, 20000, 20000);
    HttpHandle connection(WinHttpConnect(session.handle, kReleaseHost, INTERNET_DEFAULT_HTTPS_PORT, 0));
    if (!connection.handle) {
        error = L"Couldn't connect to GitHub over HTTPS. Check your connection.";
        return false;
    }
    HttpHandle request(WinHttpOpenRequest(connection.handle, L"GET", path.c_str(), nullptr,
                                         WINHTTP_NO_REFERER, WINHTTP_DEFAULT_ACCEPT_TYPES, WINHTTP_FLAG_SECURE));
    if (!request.handle) {
        error = L"Couldn't begin the GitHub download.";
        return false;
    }
    DWORD policy = WINHTTP_OPTION_REDIRECT_POLICY_DISALLOW_HTTPS_TO_HTTP;
    if (!WinHttpSetOption(request.handle, WINHTTP_OPTION_REDIRECT_POLICY, &policy, sizeof policy)) {
        error = L"Couldn't enforce HTTPS-only downloads.";
        return false;
    }
    DWORD redirects = 5;
    WinHttpSetOption(request.handle, WINHTTP_OPTION_MAX_HTTP_AUTOMATIC_REDIRECTS, &redirects, sizeof redirects);
    WinHttpAddRequestHeaders(request.handle, L"Accept: application/octet-stream\r\n",
                             static_cast<DWORD>(-1), WINHTTP_ADDREQ_FLAG_ADD);
    if (!WinHttpSendRequest(request.handle, WINHTTP_NO_ADDITIONAL_HEADERS, 0,
                            WINHTTP_NO_REQUEST_DATA, 0, 0, 0) ||
        !WinHttpReceiveResponse(request.handle, nullptr)) {
        error = L"Couldn't download the TV preview from GitHub. Check your connection and try again.";
        return false;
    }
    DWORD code = 0;
    DWORD length = sizeof code;
    if (!WinHttpQueryHeaders(request.handle, WINHTTP_QUERY_STATUS_CODE | WINHTTP_QUERY_FLAG_NUMBER,
                             WINHTTP_HEADER_NAME_BY_INDEX, &code, &length, WINHTTP_NO_HEADER_INDEX) ||
        code != 200) {
        error = code == 404
            ? L"The matching TV developer-preview build isn't available yet. Try again later or leave it unchecked."
            : L"GitHub didn't return the requested TV developer-preview file.";
        return false;
    }
    wchar_t sizeText[40]{};
    length = sizeof sizeText;
    uint64_t expected = 0;
    if (WinHttpQueryHeaders(request.handle, WINHTTP_QUERY_CONTENT_LENGTH, WINHTTP_HEADER_NAME_BY_INDEX,
                            sizeText, &length, WINHTTP_NO_HEADER_INDEX)) {
        // Reject malformed, missing and oversized claims; streaming below also
        // enforces the same byte cap for unknown/transfer-encoded responses.
        for (size_t i = 0; sizeText[i]; ++i) {
            if (sizeText[i] < L'0' || sizeText[i] > L'9') {
                error = L"GitHub returned an invalid download size.";
                return false;
            }
            expected = expected * 10 + (sizeText[i] - L'0');
            if (expected > limit) {
                error = L"The TV developer-preview download exceeds its safe size limit.";
                return false;
            }
        }
    }
    std::array<uint8_t, 64 * 1024> buffer{};
    uint64_t received = 0;
    const ULONGLONG started = GetTickCount64();
    for (;;) {
        DWORD count = 0;
        if (!WinHttpReadData(request.handle, buffer.data(), static_cast<DWORD>(buffer.size()), &count)) {
            error = L"The TV installer download was interrupted. Check the connection and try again.";
            return false;
        }
        if (!count) break;
        if (received > limit - count || GetTickCount64() - started > 10ull * 60 * 1000) {
            error = L"The TV installer download exceeded the size or time limit.";
            return false;
        }
        received += count;
        if (!write(buffer.data(), count, received, expected)) {
            error = L"Couldn't save or verify the TV installer download.";
            return false;
        }
    }
    if (!received || (expected && received != expected)) {
        error = L"The TV installer download is incomplete. Try again.";
        return false;
    }
    return true;
}

}  // namespace

bool validReleaseVersion(const std::wstring& version) {
    if (version.empty() || version.size() > 24) return false;
    int separators = 0;
    bool digit = false;
    for (const wchar_t ch : version) {
        if (ch >= L'0' && ch <= L'9') { digit = true; continue; }
        if (ch != L'.' || !digit || ++separators > 2) return false;
        digit = false;
    }
    return separators == 2 && digit;
}

std::wstring portableAssetName(const std::wstring& version) {
    return validReleaseVersion(version) ? L"Starlit-TV-Installer-Windows-" + version + L".exe" : L"";
}

std::wstring unpackedAssetName(const std::wstring& version) {
    return validReleaseVersion(version) ? L"Starlit-TV-Installer-Unpacked-Windows-" + version + L".zip" : L"";
}

std::wstring releaseAssetPath(const std::wstring& version, const std::wstring& file) {
    if (!validReleaseVersion(version) || file.empty() ||
        file.find_first_not_of(L"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789.-_") != std::wstring::npos ||
        file.find(L"..") != std::wstring::npos) return {};
    return L"/Beelzebub2/starlit/releases/download/tv-preview-v" + version + L"/" + file;
}

std::string checksumForAsset(const std::string& checksums, const std::string& asset) {
    if (asset.empty()) return {};
    std::string found;
    size_t offset = 0;
    while (offset < checksums.size()) {
        const size_t end = checksums.find('\n', offset);
        std::string line = checksums.substr(offset, end == std::string::npos ? end : end - offset);
        if (!line.empty() && line.back() == '\r') line.pop_back();
        if (line.size() == 66 + asset.size() && line.compare(64, 2, "  ") == 0 &&
            line.compare(66, asset.size(), asset) == 0) {
            std::string digest = line.substr(0, 64);
            if (!std::all_of(digest.begin(), digest.end(),
                             [](unsigned char c) { return std::isxdigit(c) != 0; })) return {};
            if (!found.empty()) return {};  // ambiguous/duplicated checksums are unsafe
            std::transform(digest.begin(), digest.end(), digest.begin(),
                           [](unsigned char c) { return static_cast<char>(std::tolower(c)); });
            found = std::move(digest);
        }
        if (end == std::string::npos) break;
        offset = end + 1;
    }
    return found;
}

static bool downloadVerifiedInstallerAsset(const std::wstring& version, const std::wstring& asset,
                                           const std::wstring& destination, uint64_t maxBytes,
                                           const std::function<void(uint64_t, uint64_t)>& onProgress,
                                           std::wstring& error) {
    const std::wstring manifestPath = releaseAssetPath(version, L"SHA256SUMS.txt");
    const std::wstring assetPath = releaseAssetPath(version, asset);
    if (asset.empty() || manifestPath.empty() || assetPath.empty()) {
        error = L"This setup version can't use the matching TV developer preview.";
        return false;
    }

    std::string manifest;
    if (!httpsGet(manifestPath, kMaxManifestBytes,
                  [&](const uint8_t* data, DWORD length, uint64_t, uint64_t) {
                      manifest.append(reinterpret_cast<const char*>(data), length);
                      return true;
                  }, error)) return false;
    // The validated version and fixed filename contain ASCII only.
    std::string asciiAsset;
    asciiAsset.reserve(asset.size());
    for (const wchar_t character : asset) asciiAsset.push_back(static_cast<char>(character));
    const std::string expected = checksumForAsset(manifest, asciiAsset);
    if (expected.empty()) {
        error = L"The TV preview release is missing a valid SHA-256 checksum for its Windows installer.";
        return false;
    }
    const std::wstring temporary = destination + L".download";
    FileHandle file(CreateFileW(temporary.c_str(), GENERIC_WRITE, 0, nullptr, CREATE_NEW,
                                FILE_ATTRIBUTE_NORMAL | FILE_FLAG_SEQUENTIAL_SCAN, nullptr));
    if (!file.valid()) {
        error = L"Couldn't create the temporary TV installer download file.";
        return false;
    }
    Sha256 hash;
    if (!hash.init()) {
        error = L"Windows couldn't initialize SHA-256 verification.";
        return false;
    }
    const bool downloaded = httpsGet(assetPath, maxBytes,
                                      [&](const uint8_t* data, DWORD length, uint64_t done, uint64_t total) {
                                          DWORD written = 0;
                                          if (!WriteFile(file.handle, data, length, &written, nullptr) ||
                                              written != length || !hash.update(data, length)) return false;
                                          if (onProgress) onProgress(done, total);
                                          return true;
                                      }, error);
    file.close();
    if (!downloaded || hash.finish() != expected ||
        !MoveFileExW(temporary.c_str(), destination.c_str(), MOVEFILE_REPLACE_EXISTING)) {
        if (downloaded && error.empty()) {
            error = L"The downloaded TV installer failed SHA-256 verification or couldn't be saved.";
        }
        DeleteFileW(temporary.c_str());
        return false;
    }
    return true;
}

bool downloadPortableInstaller(const std::wstring& version, const std::wstring& destination,
                               const std::function<void(uint64_t, uint64_t)>& onProgress,
                               std::wstring& error) {
    return downloadVerifiedInstallerAsset(version, portableAssetName(version),
                                          destination, kMaxPortableBytes, onProgress, error);
}

bool downloadUnpackedInstaller(const std::wstring& version, const std::wstring& destination,
                               const std::function<void(uint64_t, uint64_t)>& onProgress,
                               std::wstring& error) {
    return downloadVerifiedInstallerAsset(version, unpackedAssetName(version),
                                          destination, kMaxUnpackedArchiveBytes, onProgress, error);
}

}  // namespace setup::tvdownload
