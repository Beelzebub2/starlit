import { ScreenUtils } from "../../navigation/screen.js";
import { Router } from "../../navigation/routerState.js";
import { Platform } from "../../../platform/index.js";
import { addonRepository } from "../../../data/repository/addonRepository.js";
import { LibrarySyncService } from "../../../core/profile/librarySyncService.js";
import { withRequestTimeout } from "../../../core/network/requestTimeout.js";
import { I18n } from "../../../i18n/index.js";
function safe(value) { return String(value || "").replace(/[&<>"']/g, ch => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[ch]); }
function label(key, fallback) { return I18n.t(key, {}, { fallback }); }
export function normalizeManifestUrl(value) {
  const url = new URL(String(value || "").trim().replace(/^stremio:\/\//i, "https://"));
  if (url.protocol !== "https:" || url.username || url.password || url.hash) throw new Error("Enter an HTTPS manifest URL without embedded credentials");
  if (!url.pathname.endsWith("/manifest.json")) url.pathname = url.pathname.replace(/\/$/, "") + "/manifest.json";
  return url.href;
}
function hostnameOf(url) {
  try {
    return new URL(url).hostname;
  } catch (_) {
    return String(url || "");
  }
}
function initialsOf(name) {
  // No Unicode property escapes: Tizen 4/5 engines cannot parse them.
  const words = String(name || "").replace(/[-_.:/]+/g, " ").trim().split(/\s+/).filter(Boolean);
  const letters = words.length > 1 ? words[0][0] + words[1][0] : (words[0] || "?").slice(0, 2);
  return letters.toUpperCase();
}
const ACTION_ICONS = {
  phone: "smartphone",
  refresh: "sync",
  catalogs: "view_agenda",
  collections: "folder_special",
  back: "arrow_back"
};
// One installed add-on as a list row: its manifest name and logo when the
// manifest is cached, else the host it was installed from.
export function renderAddonRow(url, index, addon = null, enabled = true) {
  const name = addon?.displayName || addon?.name || hostnameOf(url);
  const host = hostnameOf(url);
  const meta = [host === name ? "" : host, addon?.version ? `v${addon.version}` : ""].filter(Boolean).join(" · ");
  const logo = addon?.logo
    ? `<img src="${safe(addon.logo)}" alt="" loading="lazy" decoding="async" referrerpolicy="no-referrer" />`
    : `<span>${safe(initialsOf(name))}</span>`;
  return `<li class="tv-addon-row${enabled ? "" : " is-disabled"}">
    <span class="tv-addon-logo" aria-hidden="true">${logo}</span>
    <span class="tv-addon-copy"><span class="tv-addon-name">${safe(name)}</span>${meta ? `<span class="tv-addon-meta">${safe(meta)}</span>` : ""}</span>
    <span class="tv-addon-state${enabled ? " is-on" : ""}">${safe(enabled ? label("subtitle_style_on", "On") : label("web_badge_disabled", "Disabled"))}</span>
    <button class="tv-addon-button focusable" data-focus-key="toggle:${index}" data-index="${index}" data-action="toggle">${safe(enabled ? label("web_btn_disable", "Disable") : label("web_btn_enable", "Enable"))}</button>
    <button class="tv-addon-button is-danger focusable" data-focus-key="remove:${index}" data-index="${index}" data-action="remove">${safe(label("web_btn_remove", "Remove"))}</button>
  </li>`;
}
export const PluginScreen = {
  async mount() { this.container = document.getElementById("plugin"); ScreenUtils.show(this.container); this.message = ""; this.focusKey = "field"; this.render(); },
  async refreshAddons() { await LibrarySyncService.pull(); addonRepository.invalidateInstalledAddonsCache(); this.message = "Add-ons refreshed"; this.render(); },
  async addManifest() {
    const field = this.container.querySelector("input");
    try {
      const url = normalizeManifestUrl(field.value);
      this.setStatus("Checking manifest…");
      await withRequestTimeout(async signal => {
        const response = await fetch(url, {signal,credentials:"omit",redirect:"error"});
        if (!response.ok) throw new Error("Manifest unavailable. Check the URL and try again.");
        const text = await response.text(); if(text.length > 262144) throw new Error("Manifest is too large");
        const manifest = JSON.parse(text); if (!manifest.id || !manifest.name || !Array.isArray(manifest.resources)) throw new Error("This URL does not contain a valid add-on manifest");
      },15000);
      await addonRepository.addAddon(url); await LibrarySyncService.push(); this.message="Add-on installed"; this.render();
    } catch(error) { this.setStatus(error.message || "Could not add manifest. Check your network."); }
  },
  setStatus(message) {
    this.message = message;
    const status = this.container?.querySelector("[role=status]");
    if (status) status.textContent = message;
  },
  render() {
    const urls = addonRepository.getInstalledAddonUrls();
    const cached = new Map();
    addonRepository.getCachedInstalledAddons(urls, { includeDisabled: true }).forEach((addon) => {
      if (addon?.baseUrl) cached.set(addonRepository.canonicalizeUrl(addon.baseUrl), addon);
    });
    const actions = [
      ["phone", label("tv_phone_manage_title", "Manage with phone")],
      ["refresh", label("addon_refresh_action", "Refresh Addons")],
      ["catalogs", label("addon_reorder_title", "Reorder home catalogs")],
      ["collections", label("web_tab_collections", "Collections")],
      ["back", label("action_back", "Back")]
    ].map(([action, title]) => `<button class="tv-addon-button is-action focusable" data-focus-key="${action}" data-action="${action}"><span class="material-icons" aria-hidden="true">${ACTION_ICONS[action]}</span><span>${safe(title)}</span></button>`).join("");
    const rows = urls.map((url, index) => renderAddonRow(url, index, cached.get(addonRepository.canonicalizeUrl(url)) || null, addonRepository.isAddonEnabled(url))).join("");
    this.container.innerHTML = `<main class="tv-addons">
      <header class="tv-addons-header">
        <p class="tv-addons-eyebrow">${safe(label("settings_content_discovery", "Content & Discovery"))}</p>
        <h1 class="tv-addons-title">${safe(label("web_manage_addons_title", "Manage Addons"))}</h1>
        <p class="tv-addons-lede">Add a manifest from a source you are authorized to use. Starlit includes no catalogs or stream providers.</p>
      </header>
      <section class="tv-addons-install">
        <label class="tv-addons-label" for="starlit-manifest">${safe(label("web_add_addon_url", "Add addon by URL"))}</label>
        <div class="tv-addons-install-row">
          <input id="starlit-manifest" class="tv-addons-field focusable" data-focus-key="field" type="url" autocomplete="off" placeholder="https://your-addon.example/manifest.json" maxlength="4096">
          <button class="tv-addon-button is-primary focusable" data-focus-key="add" data-action="add"><span class="material-icons" aria-hidden="true">add</span><span>${safe(label("addon_install_btn", "Install"))}</span></button>
        </div>
        <p class="tv-addons-status" role="status" aria-live="polite">${safe(this.message)}</p>
      </section>
      <div class="tv-addons-actions">${actions}</div>
      <section class="tv-addons-list">
        <h2 class="tv-addons-list-title">${safe(label("addon_installed_section", "Installed"))}<span class="tv-addons-count">${urls.length}</span></h2>
        ${urls.length ? `<ul>${rows}</ul>` : `<div class="tv-addons-empty"><span class="material-icons" aria-hidden="true">extension</span><p>${safe(label("addon_empty", "No addons installed. Add one to get started."))}</p></div>`}
      </section>
    </main>`;
    this.container.querySelectorAll("button").forEach(node=>node.addEventListener("click",()=>this.activate(node)));
    this.focusCurrent();
  },
  async activate(node) {
    const action=node.dataset.action,urls=addonRepository.getInstalledAddonUrls(),url=urls[Number(node.dataset.index)];
    if(action==="add") return this.addManifest();
    if(action==="phone") return Router.navigate("tvPhoneSetup");
    if(action==="back") return Router.back();
    if(action==="refresh") return this.refreshAddons();
    if(action==="catalogs") return Router.navigate("catalogOrder");
    if(action==="collections") return Router.navigate("collections");
    if(action==="toggle" && url) {addonRepository.setAddonEnabledStates([{url,enabled:!addonRepository.isAddonEnabled(url)}]);addonRepository.notifyAddonsChanged("settings");}
    if(action==="remove" && url) {
      await addonRepository.removeAddon(url);
      // The row is gone: land on the next one, or on the last remaining.
      const remaining = addonRepository.getInstalledAddonUrls().length;
      const index = Math.min(Number(node.dataset.index), remaining - 1);
      this.focusKey = index >= 0 ? `remove:${index}` : "add";
    }
    await LibrarySyncService.push();this.render();
  },
  // Focus survives a re-render: the node with the same key, else the first.
  focusCurrent() {
    const nodes=[...this.container.querySelectorAll(".focusable")];
    nodes.forEach(node=>node.classList.remove("focused"));
    const node=nodes.find(item=>item.dataset.focusKey===this.focusKey)||nodes[0];
    if(node){this.focusKey=node.dataset.focusKey;node.classList.add("focused");node.focus();node.scrollIntoView({block:"nearest"});}
  },
  moveFocus(direction) {
    ScreenUtils.moveFocusDirectional(this.container, direction, ".focusable");
    const node=this.container.querySelector(".focusable.focused");
    if(node){this.focusKey=node.dataset.focusKey;node.scrollIntoView({block:"nearest"});}
  },
  async onKeyDown(event) {
    if(Platform.isBackEvent(event)){event.preventDefault();await Router.back();return;}
    const editing=event.target?.tagName==="INPUT";
    if(editing && !["ArrowDown","Enter"].includes(event.key)) return;
    const direction={ArrowDown:"down",ArrowUp:"up",ArrowLeft:"left",ArrowRight:"right"}[event.key];
    if(direction){event.preventDefault();this.moveFocus(direction);}
    if(event.key==="Enter"){event.preventDefault();if(editing){this.focusKey="add";this.focusCurrent();}else await this.activate(event.target);}
  },
  cleanup(){ScreenUtils.hide(this.container);this.container.innerHTML="";}
};
