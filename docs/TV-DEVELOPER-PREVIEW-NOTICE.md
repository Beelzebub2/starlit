
### Samsung Tizen, LG webOS and TV installer — developer previews

The matching Tizen `.wgt`, webOS `.ipk` and Windows TV installer packages are
published automatically in a **separate TV developer prerelease for each normal
Starlit version**, linked from the corresponding stable release notes. They are
**for development and testing only** and are never stable update-channel assets.
Their identities, package contents and SHA-256 checksums are validated in CI,
but **they have not passed the physical-TV acceptance required for supported
stable releases**. Samsung packages are unsigned and must be signed for the
specific television. LG requires Developer Mode and authenticated pairing.

The Windows TV installer is optional and **not silently installed** with the
desktop application. Its upstream webOS CLI dependency tree still has security
advisories requiring review, so use the preview only on a trusted PC/network.
TV developer installations do not receive Store-managed automatic updates.
See the Tizen, webOS and TV-installer developer guides before installation.
