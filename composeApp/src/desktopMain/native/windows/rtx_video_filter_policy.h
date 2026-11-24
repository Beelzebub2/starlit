#pragma once

#include <optional>
#include <string>

namespace starlit {

// Fail closed for missing, HDR and unfamiliar transfer functions.
inline bool rtxKnownSdrTransfer(const std::string &transfer) {
    return transfer == "bt.1886" || transfer == "srgb" ||
        transfer == "gamma1.8" || transfer == "gamma2.0" || transfer == "gamma2.2" ||
        transfer == "gamma2.4" || transfer == "gamma2.6" || transfer == "gamma2.8";
}

inline std::string rtxVideoFilter(bool enhanceResolution, bool convertSdr) {
    if (enhanceResolution && convertSdr) {
        return "d3d11vpp=scale=2:scaling-mode=nvidia:nvidia-true-hdr";
    }
    if (enhanceResolution) return "d3d11vpp=scale=2:scaling-mode=nvidia";
    // Let mpv negotiate output depth and tag HDR only when the extension activates.
    if (convertSdr) return "d3d11vpp=nvidia-true-hdr";
    return "";
}

struct RtxVideoFilterState {
    bool enhanceResolution = false;
    bool convertHdr = false;
    bool compareOriginalResolution = false;
    bool sourceAllowsHdr = false;
    std::string applied;
    std::optional<std::string> rejected;

    void setSource(bool qualifies) {
        if (sourceAllowsHdr == qualifies) return;
        sourceAllowsHdr = qualifies;
        rejected.reset();
    }

    template <typename SetFilter>
    bool apply(bool compareOriginal, bool explicitRetry, SetFilter set) {
        const std::string wanted = rtxVideoFilter(
            enhanceResolution && !compareOriginal, convertHdr && sourceAllowsHdr
        );
        if (wanted != applied) {
            if (!explicitRetry && rejected && *rejected == wanted) return false;
            if (!set(wanted)) {
                rejected = wanted;
                return false;
            }
            applied = wanted;
        }
        rejected.reset();
        compareOriginalResolution = compareOriginal;
        return true;
    }
};

} // namespace starlit
