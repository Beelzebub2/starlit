// Keep the separately pinned webOS parser in sync with the TV codec policy
// without updating unrelated vendored service code. Fail closed if the pin
// changes this expression so the compatibility transform is reviewed again.
const originalAssCodec = String.raw`/^S_TEXT\/(?:ASS|SSA)$/i.test(text)`;
const compatibleAssCodec = String.raw`/^S_(?:TEXT\/)?(?:ASS|SSA)$/i.test(text)`;

export function adaptWebOsEmbeddedSubtitleParser(source) {
  if (source.split(originalAssCodec).length !== 2) {
    throw new Error("Expected one pinned webOS Matroska ASS codec rule");
  }
  return source.replace(originalAssCodec, compatibleAssCodec);
}
