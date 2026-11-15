import { focusWithoutScroll } from "./focusWithoutScroll.js";
import { pickDirectionalTarget } from "./directionalFocusTarget.js";
// The incoming screen fades and rises once (css/starlit-ui.css). The class is
// removed after the animation so the screen does not keep a compositor layer.
export const SCREEN_ENTER_CLASS = "tv-screen-enter";
export const SCREEN_ENTER_CLEAR_MS = 420;
export function playScreenEnter(container, schedule = setTimeout, cancel = clearTimeout) {
  const classes = container?.classList;
  if (!classes) {
    return;
  }
  if (container.__tvScreenEnterTimer) {
    cancel(container.__tvScreenEnterTimer);
  }
  classes.add(SCREEN_ENTER_CLASS);
  container.__tvScreenEnterTimer = schedule(() => {
    container.__tvScreenEnterTimer = null;
    classes.remove(SCREEN_ENTER_CLASS);
  }, SCREEN_ENTER_CLEAR_MS);
}
export const ScreenUtils = {
  // Micro-fade for fast-path TVs: returns the full enter class on capable
  // hardware, a cheap opacity-only fade class on fast-path TVs, or "" when
  // no enter is pending. Preserves existing enter/back semantics at every
  // call site (pending flags already encode them).
  routeEnterClass(host = null, pending = false, fullClass = "") {
    if (!pending) {
      return "";
    }
    return this.shouldSkipRouteEnter(host) ? " tizen-fade-enter" : fullClass;
  },
  isTizenFastPath(host = null) {
    return this.shouldSkipRouteEnter(host);
  },
  shouldSkipRouteEnter(host = null) {
    try {
      if (typeof host?.isPerformanceConstrained === "function" && host.isPerformanceConstrained()) {
        return true;
      }
      if (typeof host?.isLegacyTvRuntime === "function" && host.isLegacyTvRuntime()) {
        return true;
      }
    } catch (_) {}
    const body = globalThis?.document?.body?.classList || null;
    const root = globalThis?.document?.documentElement?.classList || null;
    return Boolean(
      body?.contains("performance-constrained") ||
      root?.contains("performance-constrained") ||
      body?.contains("legacy-tizen") ||
      root?.contains("legacy-tizen")
    );
  },
  show(container) {
    if (!container) {
      return;
    }
    if (container.style.display !== "block") {
      container.style.display = "block";
      // A screen that was hidden has no running animation, so the enter
      // animation starts with this first displayed frame.
      playScreenEnter(container);
    }
  },
  hide(container) {
    if (!container) {
      return;
    }
    if (container.style.display !== "none") {
      container.style.display = "none";
    }
    if (container.childNodes?.length) {
      if (typeof container.replaceChildren === "function") {
        container.replaceChildren();
      } else {
        container.innerHTML = "";
      }
    }
  },
  setInitialFocus(container, selector = ".focusable") {
    const modalOpen = Boolean(globalThis?.document?.body?.classList?.contains("nuvio-modal-open"));
    if (modalOpen) {
      const existingFocused = container?.querySelector?.(".focusable.focused") || null;
      if (existingFocused instanceof HTMLElement && container?.contains(existingFocused)) {
        focusWithoutScroll(existingFocused);
        return existingFocused;
      }
      return null;
    }
    const existingFocused = container?.querySelector?.(".focusable.focused") || null;
    if (existingFocused instanceof HTMLElement && container?.contains(existingFocused)) {
      focusWithoutScroll(existingFocused);
      return existingFocused;
    }
    const first = container?.querySelector(selector);
    if (!first) {
      return;
    }
    first.classList.add("focused");
    first.focus();
  },
  moveFocus(container, direction, selector = ".focusable") {
    if (globalThis?.document?.body?.classList?.contains("nuvio-modal-open")) {
      return;
    }
    const list = Array.from(container?.querySelectorAll(selector) || []);
    const current = container?.querySelector(`${selector}.focused`);
    if (!list.length || !current) {
      return;
    }
    const index = Number(current.dataset.index || 0);
    const nextIndex = index + direction;
    if (nextIndex < 0 || nextIndex >= list.length) {
      return;
    }
    current.classList.remove("focused");
    list[nextIndex].classList.add("focused");
    focusWithoutScroll(list[nextIndex]);
  },
  moveFocusDirectional(container, direction, selector = ".focusable") {
    if (globalThis?.document?.body?.classList?.contains("nuvio-modal-open")) {
      return;
    }
    // Reuse geometry only within this event, before any focus classes change.
    const rects = new Map();
    const list = Array.from(container?.querySelectorAll(selector) || []).filter((node) => {
      const rect = node.getBoundingClientRect();
      rects.set(node, rect);
      return rect.width > 0 && rect.height > 0;
    });
    if (!list.length) {
      return;
    }
    const current = container?.querySelector(`${selector}.focused`) || list[0];
    if (!current.classList.contains("focused")) {
      list.forEach((node) => node.classList.remove("focused"));
      current.classList.add("focused");
      focusWithoutScroll(current);
      return;
    }
    const target = pickDirectionalTarget(list, current, direction, rects);
    if (!target) {
      return;
    }
    current.classList.remove("focused");
    target.classList.add("focused");
    focusWithoutScroll(target);
  },
  handleDpadNavigation(event, container, selector = ".focusable") {
    if (globalThis?.document?.body?.classList?.contains("nuvio-modal-open")) {
      return false;
    }
    const code = Number(event?.keyCode || 0);
    const direction =
      code === 38
        ? "up"
        : code === 40
          ? "down"
          : code === 37
            ? "left"
            : code === 39
              ? "right"
              : null;
    if (!direction) {
      return false;
    }
    if (typeof event?.preventDefault === "function") {
      event.preventDefault();
    }
    this.moveFocusDirectional(container, direction, selector);
    return true;
  },
  indexFocusables(container, selector = ".focusable") {
    const list = Array.from(container?.querySelectorAll(selector) || []);
    list.forEach((node, index) => {
      const indexValue = String(index);
      if (node.dataset.index !== indexValue) {
        node.dataset.index = indexValue;
      }
      if (node.tabIndex !== 0) {
        node.tabIndex = 0;
      }
    });
  }
};
