"use strict";
const { contextBridge, ipcRenderer } = require("electron");
contextBridge.exposeInMainWorld("starlitTV", Object.freeze({
  operate: request => ipcRenderer.invoke("tv:operate", request),
  choosePackage: platform => ipcRenderer.invoke("tv:choose-package", platform),
  localIps: () => ipcRenderer.invoke("tv:local-ips"),
  discoverTvs: request => ipcRenderer.invoke("tv:discover", request),
  onDiscovered: callback => {
    const handler = (_event, info) => {
      if (Number.isSafeInteger(info?.scanId) && info.device) callback(info);
    };
    ipcRenderer.on("tv:discovered", handler);
    return () => ipcRenderer.removeListener("tv:discovered", handler);
  },
  onScanStatus: callback => {
    const handler = (_event, info) => {
      if (Number.isSafeInteger(info?.scanId) && typeof info.message === "string") callback(info);
    };
    ipcRenderer.on("tv:scan-status", handler);
    return () => ipcRenderer.removeListener("tv:scan-status", handler);
  },
  openSamsungSignIn: () => ipcRenderer.invoke("tv:open-samsung-sign-in"),
  onAuth: callback => {
    const handler = (_event, info) => {
      if (info && typeof info.available === "boolean") callback({ available: info.available });
    };
    ipcRenderer.on("tv:auth", handler);
    return () => ipcRenderer.removeListener("tv:auth", handler);
  },
  onLog: callback => {
    const handler = (_event, message) => {
      if (typeof message === "string") callback(message);
    };
    ipcRenderer.on("tv:log", handler);
    return () => ipcRenderer.removeListener("tv:log", handler);
  }
}));
