import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// No service worker. ServiceWorkers require a secure context (HTTPS or localhost), and the
// bridge serves over plain HTTP on the LAN — Safari/Chrome/Firefox won't register a SW
// against `http://xgimi.local:8080`. The cleanest offline-shell pattern that doesn't drag
// in cert-trust setup is just aggressive HTTP-cache headers on the hashed assets, plus
// iOS's "Add to Home Screen" mode which keeps the static shell on disk indefinitely.
// HttpServer.java sets the appropriate Cache-Control values per asset type.
export default defineConfig({
  plugins: [react()],
  base: "./",
  build: {
    outDir: "../app/assets/web",
    emptyOutDir: true,
    assetsInlineLimit: 4096,
  },
});
