"use strict";
const { contextBridge, ipcRenderer } = require("electron");
contextBridge.exposeInMainWorld("starlitTV", Object.freeze({
  operate: request => ipcRenderer.invoke("tv:operate", request),
  choosePackage: platform => ipcRenderer.invoke("tv:choose-package", platform),
  localIps: () => ipcRenderer.invoke("tv:local-ips"),
  discoverTvs: () => ipcRenderer.invoke("tv:discover"),
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
