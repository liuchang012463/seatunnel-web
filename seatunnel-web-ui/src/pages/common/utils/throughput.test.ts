import { formatMetric, toMegabytes } from "./throughput";

describe("formatMetric", () => {
  it("rounds up any positive remainder and keeps exactly one decimal", () => {
    expect(formatMetric("10.9078")).toBe("11.0");
    expect(formatMetric(1.2)).toBe("1.2");
    expect(formatMetric(0)).toBe("0.0");
    expect(formatMetric(0.1 + 0.2)).toBe("0.3");
  });

  it("formats decimal megabytes and keeps missing measurements distinct from zero", () => {
    expect(formatMetric(toMegabytes(1), "MB/s")).toBe("0.1 MB/s");
    expect(formatMetric(toMegabytes(1_200_000), "MB")).toBe("1.2 MB");
    expect(formatMetric(undefined, "MB/s")).toBe("—");
    expect(formatMetric("", "MB")).toBe("—");
  });
});
