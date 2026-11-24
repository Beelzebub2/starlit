/* eslint-disable no-unused-vars */
import * as internals from "./settingsScreenContext.js";
// How long the section list waits for focus to settle before rebuilding the
// section: longer than a remote's key repeat (about 100 ms), so a held key
// skips the sections it passes, yet short enough that one press feels
// instant. Constrained TVs build a section more slowly and wait a bit longer.
export const NAV_SELECTION_SETTLE_MS = Object.freeze({ standard: 120, constrained: 170 });
export function getNavSelectionSettleMs(constrained = false) {
  return constrained ? NAV_SELECTION_SETTLE_MS.constrained : NAV_SELECTION_SETTLE_MS.standard;
}
export function applyNavGliderBox(glider, box) {
  const style = glider?.style;
  if (!style || !box) {
    return;
  }
  const width = Math.round(box.width) + "px";
  const height = Math.round(box.height) + "px";
  if (style.width !== width) style.width = width;
  if (style.height !== height) style.height = height;
  const transform = "translate3d(" + Math.round(box.x) + "px, " + Math.round(box.y) + "px, 0)";
  style.webkitTransform = transform;
  style.transform = transform;
}
export function createSettingsScreenMethods13() {
  const {
    Router,
    ScreenUtils,
    getRootSidebarNodes,
    getRootSidebarSelectedNode,
    setModernSidebarExpanded,
    setLegacySidebarExpanded,
    clamp,
    focusKeySelector,
    scrollSettingsContentItem,
    updateSettingsMarqueeTargets,
    scrollSettingsRailItem,
    updateSettingsRailIndicators,
    focusSettingsNode,
    isScrollContainerAtBoundary,
    isAppearanceThemeFocusKey
  } = internals;
  return {
    applyFocus() {
      this.applyFocusState();
      this.syncNavGlider();
    },
    // One highlight slides between the section list items (css/starlit-ui.css
    // .settings-nav-glider) instead of each item repainting its own fill. It
    // follows the focused item, or rests on the open section otherwise.
    syncNavGlider() {
      const rail = this.container?.querySelector?.("[data-settings-nav]");
      if (!rail) {
        return;
      }
      const horizon = String(this.model?.theme?.settingsUiStyle || "CLASSIC") === "HORIZON";
      const navFocused = this.focusZone === "nav" && !this.optionDialog && !this.textDialog;
      const focusedItem = navFocused ? rail.querySelector(".settings-nav-item.focused") : null;
      const target = horizon ? null : focusedItem || rail.querySelector(".settings-nav-item.selected");
      let glider = this.navGlider && this.navGlider.parentNode === rail ? this.navGlider : null;
      if (!target || target.offsetParent !== rail) {
        if (glider?.parentNode) {
          glider.parentNode.removeChild(glider);
        }
        this.navGlider = null;
        rail.classList.remove("has-glider");
        return;
      }
      const box = {
        x: target.offsetLeft,
        y: target.offsetTop,
        width: target.offsetWidth,
        height: target.offsetHeight
      };
      if (!glider) {
        glider = document.createElement("span");
        glider.className = "settings-nav-glider";
        glider.setAttribute("aria-hidden", "true");
        rail.appendChild(glider);
        this.navGlider = glider;
        // Fresh rail markup: start where the previous glider stopped (or on
        // the target) without a transition, then slide from there.
        glider.classList.add("is-snapping");
        applyNavGliderBox(glider, this.navGliderBox || box);
        void glider.offsetWidth;
        glider.classList.remove("is-snapping");
      }
      glider.classList.toggle("is-resting", !focusedItem);
      applyNavGliderBox(glider, box);
      this.navGliderBox = box;
      rail.classList.add("has-glider");
    },
    applyFocusState() {
      this.container.querySelectorAll(".focusable.focused").forEach((node) => node.classList.remove("focused"));
      updateSettingsMarqueeTargets(this.container);
      const selectedNode = this.container.querySelector(".settings-nav-item.selected");
      if (selectedNode && this.focusZone !== "nav") {
        scrollSettingsRailItem(selectedNode);
      }
      if (this.optionDialog) {
        const dialogNode =
          this.container.querySelector(`.settings-dialog-option[data-dialog-index="${this.dialogFocusIndex}"]`) ||
          this.container.querySelector(".settings-dialog-option");
        if (dialogNode) {
          dialogNode.classList.add("focused");
          focusSettingsNode(dialogNode);
          scrollSettingsContentItem(dialogNode);
        }
        return;
      }
      if (this.textDialog) {
        const dialogNode =
          this.dialogFocusIndex === 0
            ? this.container.querySelector("[data-text-dialog-role='field']")
            : this.container.querySelector(`.settings-text-dialog-button[data-dialog-index="${this.dialogFocusIndex}"]`);
        if (dialogNode) {
          dialogNode.classList.add("focused");
          focusSettingsNode(dialogNode);
          if (dialogNode.matches?.("[data-text-dialog-role='field']")) {
            try {
              const length = String(dialogNode.value || "").length;
              dialogNode.setSelectionRange?.(length, length);
            } catch (_) {
              // Ignore unsupported selection APIs on TV browsers.
            }
          }
          scrollSettingsContentItem(dialogNode);
        }
        return;
      }
      if (this.focusZone === "sidebar") {
        const sidebarNodes = getRootSidebarNodes(this.container, this.layoutPrefs);
        const sidebarNode = sidebarNodes[this.sidebarFocusIndex] || getRootSidebarSelectedNode(this.container, this.layoutPrefs);
        if (sidebarNode) {
          sidebarNode.classList.add("focused");
          focusSettingsNode(sidebarNode);
          if (!this.layoutPrefs?.modernSidebar) {
            setLegacySidebarExpanded(this.container, true);
          }
          return;
        }
        this.focusZone = "nav";
      }
      if (!this.layoutPrefs?.modernSidebar) {
        setLegacySidebarExpanded(this.container, false);
      }
      if (this.focusZone === "content") {
        const contentNode = this.contentFocusKey
          ? this.container.querySelector(focusKeySelector(".settings-content-focusable", this.contentFocusKey))
          : null;
        const fallbackContent = contentNode || this.container.querySelector(".settings-content-focusable");
        if (fallbackContent) {
          fallbackContent.classList.add("focused");
          focusSettingsNode(fallbackContent);
          if (this.suppressNextContentFocusScroll) {
            this.suppressNextContentFocusScroll = false;
          } else {
            scrollSettingsContentItem(fallbackContent);
          }
          this.contentFocusKey = String(fallbackContent.dataset.focusKey || "");
          return;
        }
        this.focusZone = "nav";
      }
      const navNode =
        this.container.querySelector(`.settings-nav-item[data-nav-index="${this.navIndex}"]`) ||
        this.container.querySelector(".settings-nav-item");
      if (navNode) {
        navNode.classList.add("focused");
        focusSettingsNode(navNode);
        const rail = navNode.closest?.(".settings-sidebar");
        // Only the vertical rail restores its scroll position; the horizontal one has to follow focus.
        const horizontalRail = Boolean(rail && rail.scrollWidth > rail.clientWidth + 1);
        if (this.suppressNextRailFocusScroll && !horizontalRail) {
          this.suppressNextRailFocusScroll = false;
          updateSettingsRailIndicators(rail);
        } else {
          this.suppressNextRailFocusScroll = false;
          scrollSettingsRailItem(navNode);
        }
        updateSettingsMarqueeTargets(this.container);
      }
    },
    async openSidebar() {
      this.focusZone = "sidebar";
      const sidebarNodes = getRootSidebarNodes(this.container, this.layoutPrefs);
      const selectedSidebarNode = getRootSidebarSelectedNode(this.container, this.layoutPrefs);
      this.sidebarFocusIndex = Math.max(0, sidebarNodes.indexOf(selectedSidebarNode));
      if (this.layoutPrefs?.modernSidebar && !this.sidebarExpanded) {
        this.sidebarExpanded = true;
        setModernSidebarExpanded(this.container, true);
      }
      this.applyFocus();
    },
    async closeSidebarToNav() {
      this.syncNavFocusToActive();
      this.focusZone = "nav";
      if (this.layoutPrefs?.modernSidebar && this.sidebarExpanded) {
        this.sidebarExpanded = false;
        setModernSidebarExpanded(this.container, false);
      }
      this.applyFocus();
    },
    moveNavFocus(index) {
      this.navIndex = clamp(index, 0, this.visibleSections.length - 1);
      const section = this.visibleSections[this.navIndex];
      // The highlight moves at once; the section itself is rebuilt only when
      // focus settles, so holding Up/Down never rebuilds every section passed.
      if (section && this.activeSection !== section.id) {
        this.applyNavHighlight();
        this.scheduleNavSelection();
      } else {
        this.cancelNavSelection();
        this.applyFocus();
      }
    },
    // Highlight-only move inside the section list, for the steps before the
    // section is rebuilt; the full applyFocus runs after the rebuild.
    applyNavHighlight() {
      const rail = this.container?.querySelector?.("[data-settings-nav]");
      const navNode = rail?.querySelector?.(`.settings-nav-item[data-nav-index="${this.navIndex}"]`);
      if (!navNode || this.focusZone !== "nav" || this.optionDialog || this.textDialog) {
        this.applyFocus();
        return;
      }
      this.container.querySelectorAll(".focusable.focused").forEach((node) => {
        if (node !== navNode) node.classList.remove("focused");
      });
      navNode.classList.add("focused");
      focusSettingsNode(navNode);
      scrollSettingsRailItem(navNode);
      this.syncNavGlider();
    },
    scheduleNavSelection() {
      this.cancelNavSelection();
      const token = (this.navSelectionToken || 0) + 1;
      this.navSelectionToken = token;
      this.navSelectionTimer = setTimeout(() => {
        this.navSelectionTimer = null;
        if (this.navSelectionToken !== token || !this.isMounted) {
          return;
        }
        void this.activateNavSelection().catch((error) => console.warn("Settings section could not open", error));
      }, getNavSelectionSettleMs(ScreenUtils.isTizenFastPath(this)));
    },
    cancelNavSelection() {
      if (this.navSelectionTimer) {
        clearTimeout(this.navSelectionTimer);
        this.navSelectionTimer = null;
      }
    },
    // Any key other than another step through the list opens the pending
    // section first, so Right, OK and Back act on what the highlight shows.
    async flushNavSelection() {
      if (!this.navSelectionTimer) {
        return false;
      }
      this.cancelNavSelection();
      await this.activateNavSelection();
      return true;
    },
    isNavStepKey(code) {
      if (this.focusZone !== "nav" || this.optionDialog || this.textDialog) {
        return false;
      }
      const horizon = String(this.model?.theme?.settingsUiStyle || "CLASSIC") === "HORIZON";
      return horizon ? code === 37 || code === 39 : code === 38 || code === 40;
    },
    async activateNavSelection() {
      const section = this.visibleSections[this.navIndex];
      if (!section) {
        return;
      }
      this.setActiveSection(section.id);
      this.integrationView = "hub";
      this.contentFocusKey = section.id === "appearance" ? this.getAppearanceThemeFocusKey() : null;
      await this.render({ refreshModel: false });
    },
    syncNavFocusToActive() {
      const activeIndex = this.visibleSections.findIndex((item) => item.id === this.activeSection);
      if (activeIndex >= 0) {
        this.navIndex = activeIndex;
      }
    },
    updateFocusedContentKey() {
      const focused = this.container.querySelector(".settings-content-focusable.focused");
      if (focused) {
        this.contentFocusKey = String(focused.dataset.focusKey || "");
        this.rememberAppearanceThemeFocusKey(this.contentFocusKey);
      }
    },
    moveContent(direction) {
      const before = this.container.querySelector(".settings-content-focusable.focused");
      const beforeFocusKey = String(before?.dataset?.focusKey || "");
      if (this.activeSection === "appearance" && direction === "up" && beforeFocusKey === "appearance:font") {
        const rememberedTheme =
          this.container.querySelector(focusKeySelector(".settings-content-focusable", this.getAppearanceThemeFocusKey())) ||
          this.container.querySelector(".settings-theme-card.settings-content-focusable");
        if (rememberedTheme) {
          before?.classList?.remove("focused");
          rememberedTheme.classList.add("focused");
          focusSettingsNode(rememberedTheme);
          this.contentFocusKey = String(rememberedTheme.dataset.focusKey || "");
          this.rememberAppearanceThemeFocusKey(this.contentFocusKey);
          scrollSettingsContentItem(rememberedTheme);
          return before !== rememberedTheme;
        }
      }
      if (this.activeSection === "appearance" && direction === "down" && isAppearanceThemeFocusKey(beforeFocusKey)) {
        const themeCards = Array.from(this.container.querySelectorAll(".settings-theme-card.settings-content-focusable"));
        const beforeRect = before?.getBoundingClientRect?.();
        const beforeCenterY = beforeRect ? beforeRect.top + beforeRect.height / 2 : 0;
        const beforeCenterX = beforeRect ? beforeRect.left + beforeRect.width / 2 : 0;
        const themeBelow = themeCards
          .filter((card) => card !== before)
          .map((card) => {
            const rect = card.getBoundingClientRect();
            const centerY = rect.top + rect.height / 2;
            const centerX = rect.left + rect.width / 2;
            return {
              card,
              verticalDistance: centerY - beforeCenterY,
              horizontalDistance: Math.abs(centerX - beforeCenterX)
            };
          })
          .filter((entry) => entry.verticalDistance > 2)
          .sort((left, right) => {
            if (left.verticalDistance !== right.verticalDistance) {
              return left.verticalDistance - right.verticalDistance;
            }
            return left.horizontalDistance - right.horizontalDistance;
          });
        const nextTheme = themeBelow[0]?.card || null;
        if (nextTheme) {
          before?.classList?.remove("focused");
          nextTheme.classList.add("focused");
          focusSettingsNode(nextTheme);
          this.contentFocusKey = String(nextTheme.dataset.focusKey || "");
          this.rememberAppearanceThemeFocusKey(this.contentFocusKey);
          scrollSettingsContentItem(nextTheme);
          return before !== nextTheme;
        }
      }
      ScreenUtils.moveFocusDirectional(this.container, direction, ".settings-content-focusable");
      const after = this.container.querySelector(".settings-content-focusable.focused");
      if (after) {
        this.contentFocusKey = String(after.dataset.focusKey || "");
        if (isAppearanceThemeFocusKey(beforeFocusKey)) {
          this.rememberAppearanceThemeFocusKey(beforeFocusKey);
        }
        this.rememberAppearanceThemeFocusKey(this.contentFocusKey);
        scrollSettingsContentItem(after);
      }
      return before !== after;
    },
    handleWheelEvent(event) {
      const themeGrid = event?.target?.closest?.(".settings-theme-grid");
      if (!themeGrid) {
        return;
      }
      const deltaY = Number(event.deltaY || 0);
      if (!deltaY) {
        return;
      }
      const direction = deltaY < 0 ? "up" : "down";
      if (!isScrollContainerAtBoundary(themeGrid, direction)) {
        return;
      }
      const content = themeGrid.closest(".settings-content");
      if (!content) {
        return;
      }
      event.preventDefault();
      if (typeof content.scrollBy === "function") {
        content.scrollBy({
          top: deltaY,
          behavior: "auto"
        });
        return;
      }
      content.scrollTop += deltaY;
    },
    async handleClickEvent(event) {
      const target = event?.target?.closest?.(
        ".settings-nav-item, .settings-content-focusable, .settings-dialog-option, [data-text-dialog-role='field']"
      );
      if (!target || !this.container?.contains?.(target)) {
        return;
      }
      event?.preventDefault?.();
      if (target.classList.contains("settings-nav-item")) {
        const navIndex = Number(target.dataset.navIndex);
        if (Number.isFinite(navIndex)) {
          this.navIndex = clamp(navIndex, 0, Math.max(0, this.visibleSections.length - 1));
        }
        this.focusZone = "nav";
        await this.activateNavSelection();
        return;
      }
      this.container.querySelectorAll(".focusable.focused").forEach((node) => node.classList.remove("focused"));
      target.classList.add("focused");
      focusSettingsNode(target);
      if (String(target.dataset.focusKey || "") === "about:supporters") {
        await Router.navigate("supportersContributors");
        return;
      }
      if (this.textDialog) {
        const field = target.closest?.("[data-text-dialog-role='field']");
        if (field) {
          this.focusZone = "dialog";
          this.dialogFocusIndex = 0;
          return;
        }
        const button = target.closest?.(".settings-text-dialog-button[data-dialog-index]");
        if (button) {
          this.focusZone = "dialog";
          this.dialogFocusIndex = clamp(Number(button.dataset.dialogIndex || 1), 1, this.getTextDialogMaxFocusIndex());
          await this.activateFocused();
          return;
        }
        return;
      }
      if (target.classList.contains("settings-dialog-option")) {
        const dialogIndex = Number(target.dataset.dialogIndex);
        if (Number.isFinite(dialogIndex)) {
          this.dialogFocusIndex = dialogIndex;
        }
      } else {
        this.focusZone = "content";
        this.contentFocusKey = String(target.dataset.focusKey || this.contentFocusKey || "");
      }
      await this.activateFocused();
    }
  };
}
