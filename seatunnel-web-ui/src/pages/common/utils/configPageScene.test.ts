import { resolveConfigPageScene } from "./configPageScene";

describe("resolveConfigPageScene", () => {
  it("uses create mode only for an explicit create scene", () => {
    expect(resolveConfigPageScene("create")).toBe("create");
  });

  it.each(["edit", null, "unknown"])(
    "uses edit mode for %s scene values",
    (scene) => {
      expect(resolveConfigPageScene(scene)).toBe("edit");
    },
  );
});
