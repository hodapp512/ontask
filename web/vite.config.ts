import { defineConfig } from "vite";
// Built straight into the Firebase project folder: Hosting only serves files inside it.
export default defineConfig({ build: { outDir: "../firebase/public", emptyOutDir: true, sourcemap: true } });
