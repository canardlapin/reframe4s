#!/usr/bin/env node

import assert from "node:assert/strict";

import { lockedPaths } from "./export-motion-lock-paths.mjs";

assert.deepEqual(
  lockedPaths(
    {
      benchmarkAssets: { manifestPath: "assets.json" },
      environmentManifestPath: "environment.json",
    },
    "/locked/admission.json",
  ),
  {
    MOTION_ASSETS_PATH: "/locked/assets.json",
    MOTION_ENVIRONMENT_MANIFEST_PATH: "/locked/environment.json",
  },
);

console.log("motion lock-path export tests: ok");
