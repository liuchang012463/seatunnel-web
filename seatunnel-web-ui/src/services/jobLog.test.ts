import { parseJobLogDiagnosisSseBlock } from "./jobLog";

describe("parseJobLogDiagnosisSseBlock", () => {
  it("parses a JSON SSE data event and ignores comments", () => {
    expect(parseJobLogDiagnosisSseBlock(
      ": keep-alive\ndata: {\"type\":\"delta\",\"content\":\"实时输出\"}",
    )).toEqual({ type: "delta", content: "实时输出" });
  });

  it("returns null for an SSE comment without data", () => {
    expect(parseJobLogDiagnosisSseBlock(": keep-alive")).toBeNull();
  });
});
