#include <cassert>
#include <iostream>
#include <string>
#include <vector>
#include "../composeApp/src/desktopMain/native/windows/rtx_video_filter_policy.h"

using starlit::RtxVideoFilterState;
using starlit::rtxKnownSdrTransfer;
using starlit::rtxVideoFilter;

int main() {
    for (auto *gamma : {"bt.1886", "srgb", "gamma2.2", "gamma2.4"})
        assert(rtxKnownSdrTransfer(gamma));
    for (auto *gamma : {"", "auto", "pq", "hlg", "scrgb", "linear", "st428", "future"})
        assert(!rtxKnownSdrTransfer(gamma));

    RtxVideoFilterState state{};
    state.enhanceResolution = true;
    state.convertHdr = true;
    state.applied = rtxVideoFilter(true, false);
    std::vector<std::string> calls;
    bool allow = true;
    auto set = [&](const std::string &wanted) { calls.push_back(wanted); return allow; };
    state.setSource(true);
    assert(state.apply(false, false, set));
    assert(state.applied == "d3d11vpp=scale=2:scaling-mode=nvidia:nvidia-true-hdr");
    assert(state.apply(true, true, set));
    assert(state.applied == "d3d11vpp=nvidia-true-hdr");
    assert(state.compareOriginalResolution);
    assert(state.apply(state.compareOriginalResolution, false, set));
    assert(calls.size() == 2);

    state.setSource(false);
    assert(state.apply(state.compareOriginalResolution, false, set));
    assert(state.applied.empty());
    assert(state.apply(false, true, set));
    assert(state.applied == "d3d11vpp=scale=2:scaling-mode=nvidia");

    state.setSource(true);
    allow = false;
    assert(!state.apply(false, false, set));
    assert(state.applied == "d3d11vpp=scale=2:scaling-mode=nvidia");
    const auto attempts = calls.size();
    assert(!state.apply(false, false, set));
    assert(calls.size() == attempts);
    assert(!state.apply(true, true, set));
    assert(!state.compareOriginalResolution);
    assert(state.applied == "d3d11vpp=scale=2:scaling-mode=nvidia");
    allow = true;
    assert(state.apply(true, true, set));
    assert(state.compareOriginalResolution);
    assert(state.applied == "d3d11vpp=nvidia-true-hdr");

    RtxVideoFilterState baseline{};
    assert(baseline.apply(false, false, [](const auto &) { assert(false); return false; }));
    assert(rtxVideoFilter(false, true).find("transfer=")==std::string::npos);
    std::cout << "RTX policy tests passed\n";
}
