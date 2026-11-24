"use strict";

// Apps2Samsung v3.3.0 prints the OAuth URL to its CLI output. Only that exact
// endpoint and localhost callback are eligible to be opened by the installer.
const SIGN_IN_PATH = "/accounts/be1dce529476c1a6d407c4c7578c31bd/signInGate";
const CALLBACK_URL = "http://localhost:4794/signin/callback";

function isSamsungSignInUrl(value) {
  try {
    const url = new URL(value);
    return url.protocol === "https:" &&
      url.hostname === "account.samsung.com" &&
      url.port === "" &&
      url.username === "" && url.password === "" &&
      url.pathname === SIGN_IN_PATH &&
      url.searchParams.get("redirect_uri") === CALLBACK_URL &&
      url.searchParams.get("clientId") === "v285zxnl3h";
  } catch {
    return false;
  }
}

function extractSamsungSignInUrl(text) {
  for (const match of String(text).match(/https:\/\/[^\s<>"']+/g) || []) {
    const candidate = match.replace(/[),.;]+$/, "");
    if (isSamsungSignInUrl(candidate)) return candidate;
  }
  return "";
}

module.exports = { isSamsungSignInUrl, extractSamsungSignInUrl };
