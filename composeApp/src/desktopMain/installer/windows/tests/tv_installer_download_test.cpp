#include "../tv_installer_download.h"

#include <cassert>
#include <cwchar>
#include <iostream>
#include <string>

using namespace setup::tvdownload;

int wmain(int argc, wchar_t** argv) {
    assert(validReleaseVersion(L"1.29.1"));
    assert(validReleaseVersion(L"100.0.0"));
    for (const auto* invalid : {L"", L"v1.29.1", L"1.29", L"1.29.1.0",
                                L"1..1", L".1.2", L"1.2.", L"1.2.3/../",
                                L"1.2.3?x", L"1.2.3\n"}) {
        assert(!validReleaseVersion(invalid));
    }

    assert(portableAssetName(L"1.29.1") == L"Starlit-TV-Installer-Windows-1.29.1.exe");
    assert(portableAssetName(L"v1.29.1").empty());
    assert(releaseAssetPath(L"1.29.1", L"SHA256SUMS.txt") ==
           L"/Beelzebub2/starlit/releases/download/tv-preview-v1.29.1/SHA256SUMS.txt");
    assert(releaseAssetPath(L"1.29.1", portableAssetName(L"1.29.1")) ==
           L"/Beelzebub2/starlit/releases/download/tv-preview-v1.29.1/Starlit-TV-Installer-Windows-1.29.1.exe");
    assert(releaseAssetPath(L"1.29.1", L"../escape.exe").empty());
    assert(releaseAssetPath(L"1.29.1", L"some/file.exe").empty());
    assert(releaseAssetPath(L"1.29.1", L"file with spaces.exe").empty());

    const std::string name = "Starlit-TV-Installer-Windows-1.29.1.exe";
    const std::string upper(64, 'A');
    const std::string lower(64, 'a');
    const std::string other(64, 'b');
    const std::string sums = other + "  Starlit-Tizen-1.29.1.wgt\r\n" +
                             upper + "  " + name + "\r\n" +
                             other + "  Starlit-TV-Installer-Setup-Windows-1.29.1.exe\n";
    assert(checksumForAsset(sums, name) == lower);
    assert(checksumForAsset(sums, "Starlit-TV-Installer-Setup-Windows-1.29.1.exe") == other);
    assert(checksumForAsset(sums, "Starlit-TV-Installer-Windows-1.29.2.exe").empty());
    assert(checksumForAsset(upper + "  " + name + "\n" + lower + "  " + name + "\n", name).empty());
    assert(checksumForAsset(std::string(64, 'g') + "  " + name + "\n", name).empty());
    assert(checksumForAsset(upper + " *" + name + "\n", name).empty());
    assert(checksumForAsset(upper + "  " + name + ".bak\n", name).empty());

    if (argc == 3 && std::wcscmp(argv[1], L"--integration") == 0) {
        // Explicit-only smoke test: the real v1.29.0 preview is unsigned and
        // must not be executed. The caller chooses an isolated scratch path.
        std::wstring error;
        uint64_t last = 0;
        const bool ok = downloadPortableInstaller(
            L"1.29.0", argv[2],
            [&](uint64_t done, uint64_t) { assert(done >= last); last = done; }, error);
        if (!ok) std::wcerr << error << L"\n";
        assert(ok && last > 50ull * 1024 * 1024);
        return 0;
    }
    std::cout << "TV installer release URL and SHA-256 manifest tests passed.\n";
    return 0;
}
