import { defineConfig } from "vitest/config";
export default defineConfig({ test: { include: ["test/**/*.emu.test.ts"], testTimeout: 20000 } });
