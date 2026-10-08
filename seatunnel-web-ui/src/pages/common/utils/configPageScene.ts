export type ConfigPageScene = "create" | "edit";

/** Old saved-job links may not include a scene query; treat them as edits. */
export const resolveConfigPageScene = (scene: string | null): ConfigPageScene =>
  scene === "create" ? "create" : "edit";
