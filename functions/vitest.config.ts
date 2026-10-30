import { defineConfig } from "vitest/config";
export default defineConfig({ test: { exclude: ["test/**/*.emu.test.ts", "node_modules/**"] } });
